package info.openrocket.swing.gui.live;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.document.events.DocumentChangeEvent;
import info.openrocket.core.document.events.DocumentChangeListener;
import info.openrocket.core.live.LiveDocumentCodec;
import info.openrocket.core.live.LiveInvite;
import info.openrocket.core.live.LiveProjectLink;
import info.openrocket.core.live.LiveSecureConnection;
import info.openrocket.core.live.LiveSessionEvent;
import info.openrocket.core.live.LiveSessionLog;
import info.openrocket.core.live.LiveWireMessage;
import info.openrocket.core.rocketcomponent.RocketComponent;

/** Coordinates a direct host or participant connection for one OpenRocket document. */
public final class LiveSessionManager implements Closeable {
	public record Room(String sessionId, String name, String lastActive) {
		@Override
		public String toString() {
			String displayTime = lastActive == null ? "Unknown time" : lastActive.replace('T', ' ');
			if (displayTime.length() > 16) {
				displayTime = displayTime.substring(0, 16);
			}
			return name + "  •  " + displayTime;
		}
	}

	public enum Role {
		IDLE,
		HOST,
		PARTICIPANT
	}

	public interface Listener {
		default void stateChanged(Role role, String status) {
		}

		default void eventReceived(LiveSessionEvent event) {
		}

		default void error(String message, Throwable cause) {
		}

		default void chatReceived(String participantId, String participantName, String timestamp, String text) {
		}

		default void presenceChanged(String participantId, String participantName, String surfaceId) {
		}

		default void cursorMoved(String participantId, String participantName, String surfaceId,
				double x, double y) {
		}

		default void offlineBranchProposed(String participantId, String participantName,
				Runnable accept, Runnable keepHostVersion) {
		}
	}

	private static final Logger log = LoggerFactory.getLogger(LiveSessionManager.class);
	private static final Duration CHANGE_DELAY = Duration.ofMillis(100);
	private static final Duration SAVE_DELAY = Duration.ofMillis(500);
	private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
	private static final int RECONNECT_DELAY_SECONDS = 3;
	private static final int MAX_CHAT_LENGTH = 4_000;

	private final OpenRocketDocument document;
	private final String localParticipantId = UUID.randomUUID().toString();
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "OpenRocket Live scheduler");
		thread.setDaemon(true);
		return thread;
	});
	private final ExecutorService networkExecutor = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "OpenRocket Live network");
		thread.setDaemon(true);
		return thread;
	});
	private final List<Peer> peers = new CopyOnWriteArrayList<>();
	private final List<LiveSessionEvent> history = new ArrayList<>();
	private final Set<String> loggedEventIds = new HashSet<>();
	private final Set<String> bannedParticipantIds = ConcurrentHashMap.newKeySet();
	private final DocumentChangeListener documentListener = new DocumentChangeListener() {
		@Override
		public void documentChanged(DocumentChangeEvent event) {
			onLocalDocumentChanged(event);
		}
	};

	private volatile Role role = Role.IDLE;
	private volatile long revision;
	private volatile boolean applyingRemote;
	private volatile boolean connected;
	private volatile boolean closed;
	private volatile boolean reconnectScheduled;
	private volatile boolean retryInitialConnection;
	private volatile boolean everConnected;
	private volatile boolean removedByHost;
	private String participantId;
	private String participantName;
	private String roomName;
	private String localSurfaceId;
	private LiveProjectLink.EditPolicy editPolicy = LiveProjectLink.EditPolicy.ALLOW_OFFLINE_BRANCHES;
	private LiveInvite invite;
	private LiveSessionLog sessionLog;
	private ServerSocket serverSocket;
	private LiveSecureConnection hostConnection;
	private ScheduledFuture<?> pendingChange;
	private ScheduledFuture<?> pendingSave;
	private byte[] pendingSnapshot;
	private long pendingSnapshotRevision;
	private byte[] lastAcceptedSnapshot;
	private byte[] offlineSnapshot;
	private long offlineBaseRevision;
	private Path offlineBranchFile;
	private DocumentChangeEvent latestChange;

	public LiveSessionManager(OpenRocketDocument document, Listener listener) {
		this.document = document;
		if (listener != null) {
			listeners.add(listener);
		}
	}

	public void addListener(Listener listener) {
		listeners.add(listener);
	}

	public void removeListener(Listener listener) {
		listeners.remove(listener);
	}

	public synchronized LiveInvite host(String name) throws IOException {
		return host(name, defaultRoomName(), null);
	}

	public synchronized LiveInvite host(String name, String selectedRoomName, String existingSessionId)
			throws IOException {
		return host(name, selectedRoomName, existingSessionId, null,
				LiveProjectLink.EditPolicy.ALLOW_OFFLINE_BRANCHES);
	}

	public synchronized LiveInvite host(String name, String selectedRoomName, String existingSessionId,
			LiveInvite reusableInvite, LiveProjectLink.EditPolicy selectedEditPolicy) throws IOException {
		ensureIdleAndSaved();
		participantId = localParticipantId;
		participantName = name;
		roomName = selectedRoomName == null || selectedRoomName.isBlank() ? defaultRoomName()
				: selectedRoomName.trim();
		String sessionId = existingSessionId == null ? UUID.randomUUID().toString() : existingSessionId;
		editPolicy = selectedEditPolicy == null ? LiveProjectLink.EditPolicy.ALLOW_OFFLINE_BRANCHES
				: selectedEditPolicy;
		Path logPath = LiveSessionLog.pathForDesign(document.getFile());
		if (existingSessionId != null) {
			loadRoomHistory(logPath, existingSessionId);
		}
		serverSocket = bindServerSocket(reusableInvite == null ? 0 : reusableInvite.getPort(),
				reusableInvite != null);
		invite = reusableInvite != null && sessionId.equals(reusableInvite.getSessionId())
				? new LiveInvite(findBestLocalAddress(), serverSocket.getLocalPort(), sessionId,
						reusableInvite.getSecret())
				: LiveInvite.create(findBestLocalAddress(), serverSocket.getLocalPort(), sessionId);
		sessionLog = new LiveSessionLog(logPath);
		role = Role.HOST;
		connected = true;
		document.addDocumentChangeListener(documentListener);
		if (existingSessionId != null) {
			replayHistory();
		}

		long startRevision = nextRevision();
		String action = existingSessionId == null ? "Started" : "Reopened";
		LiveSessionEvent event = LiveSessionEvent.create(invite.getSessionId(), startRevision, participantId,
				participantName, LiveSessionEvent.Type.SESSION_STARTED, "room",
				invite.getSessionId(), action + " room “" + roomName + "”", null, roomName);
		recordEvent(event);
		networkExecutor.execute(this::acceptLoop);
		fireStateChanged("Hosting “" + roomName + "”");
		return invite;
	}

	public synchronized void join(LiveInvite liveInvite, String name, File localFile) throws IOException {
		join(liveInvite, name, localFile, null, LiveProjectLink.EditPolicy.ALLOW_OFFLINE_BRANCHES, false);
	}

	public synchronized void join(LiveInvite liveInvite, String name, File localFile, String clientId,
			LiveProjectLink.EditPolicy selectedEditPolicy) throws IOException {
		join(liveInvite, name, localFile, clientId, selectedEditPolicy, false);
	}

	public synchronized void join(LiveInvite liveInvite, String name, File localFile, String clientId,
			LiveProjectLink.EditPolicy selectedEditPolicy, boolean retryInitial) throws IOException {
		if (role != Role.IDLE) {
			throw new IllegalStateException("This document is already in an OpenRocket Live session");
		}
		participantId = clientId == null ? localParticipantId : clientId;
		participantName = name;
		roomName = null;
		editPolicy = selectedEditPolicy == null ? LiveProjectLink.EditPolicy.ALLOW_OFFLINE_BRANCHES
				: selectedEditPolicy;
		retryInitialConnection = retryInitial;
		everConnected = false;
		removedByHost = false;
		invite = liveInvite;
		document.setFile(localFile);
		sessionLog = new LiveSessionLog(LiveSessionLog.pathForDesign(localFile));
		role = Role.PARTICIPANT;
		document.addDocumentChangeListener(documentListener);
		fireStateChanged("Connecting to host...");
		networkExecutor.execute(this::connectToHost);
	}

	public Role getRole() {
		return role;
	}

	public LiveInvite getInvite() {
		return invite;
	}

	public long getRevision() {
		return revision;
	}

	public boolean isConnected() {
		return connected;
	}

	public boolean hasEverConnected() {
		return everConnected;
	}

	public String getParticipantId() {
		return participantId;
	}

	public String getParticipantName() {
		return participantName;
	}

	public String getRoomName() {
		return roomName;
	}

	public LiveProjectLink.EditPolicy getEditPolicy() {
		return editPolicy;
	}

	public void setEditPolicy(LiveProjectLink.EditPolicy policy) {
		if (role != Role.HOST || policy == null) {
			throw new IllegalStateException("Only the host can change Live room edit settings");
		}
		editPolicy = policy;
		broadcastTransient(LiveWireMessage.settings(invite.getSessionId(), policy.name()), null);
		fireStateChanged("Hosting “" + roomName + "”");
	}

	public List<Room> getAvailableRooms() throws IOException {
		if (document.getFile() == null) {
			return Collections.emptyList();
		}
		Map<String, Room> rooms = new LinkedHashMap<>();
		for (LiveSessionEvent event : LiveSessionLog.read(LiveSessionLog.pathForDesign(document.getFile()))) {
			Room previous = rooms.get(event.getSessionId());
			String name = previous == null ? "Room " + shortId(event.getSessionId()) : previous.name();
			if (event.getType() == LiveSessionEvent.Type.SESSION_STARTED
					&& event.getNewValue() != null && !event.getNewValue().isBlank()) {
				name = event.getNewValue();
			}
			rooms.put(event.getSessionId(), new Room(event.getSessionId(), name, event.getTimestamp()));
		}
		List<Room> result = new ArrayList<>(rooms.values());
		result.sort((first, second) -> second.lastActive().compareTo(first.lastActive()));
		return result;
	}

	public synchronized List<LiveSessionEvent> getHistory() {
		return Collections.unmodifiableList(new ArrayList<>(history));
	}

	private void ensureIdleAndSaved() {
		if (role != Role.IDLE) {
			throw new IllegalStateException("This document is already in an OpenRocket Live session");
		}
		if (document.getFile() == null) {
			throw new IllegalStateException("Save the OpenRocket design before hosting a Live session");
		}
	}

	private void loadRoomHistory(Path logPath, String sessionId) throws IOException {
		for (LiveSessionEvent event : LiveSessionLog.read(logPath)) {
			if (!sessionId.equals(event.getSessionId())) {
				continue;
			}
			history.add(event);
			loggedEventIds.add(event.getEventId());
			revision = Math.max(revision, event.getRevision());
			if (event.getType() == LiveSessionEvent.Type.PARTICIPANT_BANNED
					&& event.getTargetId() != null) {
				bannedParticipantIds.add(event.getTargetId());
			}
		}
	}

	private String defaultRoomName() {
		String name = document.getFile().getName();
		String lower = name.toLowerCase();
		if (lower.endsWith(".ork.gz")) {
			return name.substring(0, name.length() - 7);
		}
		if (lower.endsWith(".ork")) {
			return name.substring(0, name.length() - 4);
		}
		return name;
	}

	private static String shortId(String sessionId) {
		return sessionId.substring(0, Math.min(8, sessionId.length()));
	}

	private static ServerSocket bindServerSocket(int requestedPort, boolean allowFallback) throws IOException {
		try {
			return createBoundServerSocket(requestedPort);
		} catch (IOException firstFailure) {
			if (!allowFallback) {
				throw firstFailure;
			}
			return createBoundServerSocket(0);
		}
	}

	private static ServerSocket createBoundServerSocket(int port) throws IOException {
		ServerSocket socket = new ServerSocket();
		try {
			socket.setReuseAddress(true);
			socket.bind(new InetSocketAddress(port));
			return socket;
		} catch (IOException e) {
			closeQuietly(socket);
			throw e;
		}
	}


	private void acceptLoop() {
		while (!closed && role == Role.HOST) {
			try {
				Socket socket = serverSocket.accept();
				socket.setTcpNoDelay(true);
				networkExecutor.execute(() -> acceptPeer(socket));
			} catch (IOException e) {
				if (!closed && role == Role.HOST) {
					fireError("Could not accept an OpenRocket Live participant", e);
				}
			}
		}
	}

	private void acceptPeer(Socket socket) {
		Peer peer = null;
		try {
			socket.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
			LiveSecureConnection connection = LiveSecureConnection.accept(socket, invite.getSecret());
			LiveWireMessage hello = connection.receive();
			if (hello == null || hello.getType() != LiveWireMessage.Type.HELLO
					|| !invite.getSessionId().equals(hello.getSessionId())) {
				throw new IOException("Invalid OpenRocket Live greeting");
			}
			if (bannedParticipantIds.contains(hello.getParticipantId())) {
				connection.send(LiveWireMessage.error("You are banned from this Live room"));
				return;
			}
			socket.setSoTimeout(0);
			peer = new Peer(hello.getParticipantId(), hello.getParticipantName(), connection);
			peers.add(peer);
			long joinRevision = nextRevision();
			LiveSessionEvent joined = LiveSessionEvent.create(invite.getSessionId(), joinRevision,
					hello.getParticipantId(), hello.getParticipantName(), LiveSessionEvent.Type.PARTICIPANT_JOINED,
					"session", invite.getSessionId(), hello.getParticipantName() + " joined", null, null);
			recordEvent(joined);
			byte[] snapshot = encodeOnEventThread();
			connection.send(LiveWireMessage.welcome(invite.getSessionId(), roomName, editPolicy.name(),
					revision, snapshot, getHistory()));
			sendKnownPresence(peer);
			LiveWireMessage joinedUpdate = LiveWireMessage.update(invite.getSessionId(), revision, joined, snapshot);
			for (Peer existingPeer : peers) {
				if (existingPeer != peer) {
					networkExecutor.execute(() -> send(existingPeer, joinedUpdate));
				}
			}

			LiveWireMessage message;
			while ((message = connection.receive()) != null && role == Role.HOST) {
				if (message.getType() == LiveWireMessage.Type.PROPOSE_DOCUMENT) {
					handleProposal(peer, message);
				} else if (message.getType() == LiveWireMessage.Type.PROPOSE_OFFLINE_BRANCH) {
					handleOfflineProposal(peer, message);
				} else if (message.getType() == LiveWireMessage.Type.CHAT
						|| message.getType() == LiveWireMessage.Type.PRESENCE
						|| message.getType() == LiveWireMessage.Type.CURSOR) {
					handleParticipantMessage(peer, message);
				}
			}
		} catch (IOException e) {
			if (!closed && role == Role.HOST) {
				log.info("OpenRocket Live participant disconnected: {}", e.getMessage());
			}
		} finally {
			if (peer != null) {
				participantDisconnected(peer);
				closeQuietly(peer.connection);
			}
			closeQuietly(socket);
		}
	}

	private void handleProposal(Peer peer, LiveWireMessage proposal) {
		OpenRocketDocument decoded;
		try {
			decoded = LiveDocumentCodec.decode(proposal.getDocument());
		} catch (Exception e) {
			fireError("A participant sent an invalid OpenRocket document", e);
			return;
		}

		SwingUtilities.invokeLater(() -> {
			if (proposal.getBaseRevision() != revision) {
				sendCurrentDocument(peer, "The design changed before your edit reached the host; resynchronized");
				return;
			}
			applyingRemote = true;
			try {
				document.applyLiveSnapshot(decoded);
			} finally {
				applyingRemote = false;
			}
			long acceptedRevision = nextRevision();
			LiveSessionEvent event = LiveSessionEvent.create(invite.getSessionId(), acceptedRevision,
					proposal.getParticipantId(), proposal.getParticipantName(),
					LiveSessionEvent.Type.DOCUMENT_CHANGED, "document", document.getRocket().getID().toString(),
					proposal.getParticipantName() + " updated the design", null, null);
			recordEvent(event);
			publishSnapshot(proposal.getDocument(), event);
		});
	}

	private void handleOfflineProposal(Peer peer, LiveWireMessage proposal) {
		OpenRocketDocument decoded;
		try {
			decoded = LiveDocumentCodec.decode(proposal.getDocument());
		} catch (Exception e) {
			fireError("A participant proposed an invalid offline branch", e);
			return;
		}

		SwingUtilities.invokeLater(() -> {
			if (role != Role.HOST || !peers.contains(peer)) {
				return;
			}
			final boolean[] decided = { false };
			Runnable accept = () -> {
				if (decided[0] || role != Role.HOST) {
					return;
				}
				decided[0] = true;
				applyingRemote = true;
				try {
					document.applyLiveSnapshot(decoded);
				} finally {
					applyingRemote = false;
				}
				long acceptedRevision = nextRevision();
				LiveSessionEvent event = LiveSessionEvent.create(invite.getSessionId(), acceptedRevision,
						proposal.getParticipantId(), proposal.getParticipantName(),
						LiveSessionEvent.Type.DOCUMENT_CHANGED, "document",
						document.getRocket().getID().toString(),
						proposal.getParticipantName() + " merged an offline branch", null, null);
				recordEvent(event);
				publishSnapshot(proposal.getDocument(), event);
			};
			Runnable keepHostVersion = () -> {
				if (decided[0] || role != Role.HOST) {
					return;
				}
				decided[0] = true;
				sendCurrentDocument(peer,
						"The host kept the current design. Your offline branch file was preserved.");
			};
			for (Listener liveListener : listeners) {
				liveListener.offlineBranchProposed(peer.participantId, peer.participantName,
						accept, keepHostVersion);
			}
		});
	}

	private void connectToHost() {
		reconnectScheduled = false;
		Socket socket = null;
		try {
			socket = new Socket();
			socket.connect(new InetSocketAddress(invite.getHost(), invite.getPort()), CONNECT_TIMEOUT_MILLIS);
			socket.setTcpNoDelay(true);
			socket.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
			hostConnection = LiveSecureConnection.connect(socket, invite.getSecret());
			hostConnection.send(LiveWireMessage.hello(invite.getSessionId(), participantId, participantName));

			LiveWireMessage message;
			message = hostConnection.receive();
			if (message != null && message.getType() == LiveWireMessage.Type.ERROR) {
				handleHostMessage(message);
				throw new IOException(message.getText());
			}
			if (message == null || message.getType() != LiveWireMessage.Type.WELCOME) {
				throw new IOException("The host did not accept the OpenRocket Live session");
			}
			everConnected = true;
			socket.setSoTimeout(0);
			handleHostMessage(message);
			while ((message = hostConnection.receive()) != null && role == Role.PARTICIPANT) {
				handleHostMessage(message);
			}
			if (role == Role.PARTICIPANT) {
				throw new IOException("The host closed the Live connection");
			}
		} catch (IOException e) {
			if (!closed && role == Role.PARTICIPANT) {
				handleConnectionLost(e);
			}
		} finally {
			closeQuietly(socket);
		}
	}

	private void handleConnectionLost(IOException cause) {
		LiveSecureConnection failedConnection = hostConnection;
		hostConnection = null;
		closeQuietly(failedConnection);
		connected = false;
		if (removedByHost) {
			SwingUtilities.invokeLater(this::leaveSession);
			return;
		}
		if (!everConnected && !retryInitialConnection) {
			SwingUtilities.invokeLater(() -> {
				if (role != Role.PARTICIPANT) {
					return;
				}
				leaveSession();
				String message = "Could not join the Live session. Check that the host is reachable and on your network.";
				fireStateChanged(message);
				fireError(message, cause);
			});
			return;
		}
		fireStateChanged("Offline — reconnecting to “" + (roomName == null ? "room" : roomName) + "”…");
		scheduleReconnect();
	}

	private synchronized void scheduleReconnect() {
		if (reconnectScheduled || closed || role != Role.PARTICIPANT || connected || removedByHost) {
			return;
		}
		reconnectScheduled = true;
		scheduler.schedule(() -> {
			reconnectScheduled = false;
			if (!closed && role == Role.PARTICIPANT && !connected) {
				networkExecutor.execute(this::connectToHost);
			}
		}, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
	}

	private void handleParticipantMessage(Peer peer, LiveWireMessage incoming) {
		LiveWireMessage relayed;
		if (incoming.getType() == LiveWireMessage.Type.CHAT) {
			String text = normalizeChat(incoming.getText());
			if (text == null) {
				return;
			}
			LiveSessionEvent event = createChatEvent(peer.participantId, peer.participantName, text);
			relayed = LiveWireMessage.chat(invite.getSessionId(), peer.participantId, peer.participantName,
					text, event);
			notifyTransient(relayed);
		} else if (incoming.getType() == LiveWireMessage.Type.PRESENCE) {
			peer.surfaceId = incoming.getSurfaceId();
			relayed = LiveWireMessage.presence(invite.getSessionId(), peer.participantId, peer.participantName,
					incoming.getSurfaceId());
			notifyTransient(relayed);
		} else {
			relayed = LiveWireMessage.cursor(invite.getSessionId(), peer.participantId, peer.participantName,
					incoming.getSurfaceId(), incoming.getX(), incoming.getY());
			notifyTransient(relayed);
		}
		broadcastTransient(relayed, incoming.getType() == LiveWireMessage.Type.CHAT ? null : peer);
	}

	private void handleHostMessage(LiveWireMessage message) {
		if (message.getType() == LiveWireMessage.Type.ERROR) {
			if (message.getText() != null && (message.getText().contains("host banned you")
					|| message.getText().contains("host removed you")
					|| message.getText().contains("banned from this Live room"))) {
				removedByHost = true;
			}
			fireError(message.getText(), null);
			return;
		}
		if (message.getType() == LiveWireMessage.Type.CHAT
				|| message.getType() == LiveWireMessage.Type.PRESENCE
				|| message.getType() == LiveWireMessage.Type.CURSOR) {
			notifyTransient(message);
			return;
		}
		if (message.getType() == LiveWireMessage.Type.EVENT) {
			revision = Math.max(revision, message.getRevision());
			if (message.getEvent() != null) {
				recordEvent(message.getEvent());
			}
			return;
		}
		if (message.getType() == LiveWireMessage.Type.SETTINGS) {
			try {
				editPolicy = LiveProjectLink.EditPolicy.valueOf(message.getSettings());
				fireStateChanged("Connected to “" + roomName + "”");
			} catch (IllegalArgumentException | NullPointerException e) {
				fireError("The host sent invalid Live room settings", e);
			}
			return;
		}
		if (message.getType() != LiveWireMessage.Type.WELCOME
				&& message.getType() != LiveWireMessage.Type.DOCUMENT_UPDATE) {
			return;
		}

		OpenRocketDocument decoded;
		try {
			decoded = LiveDocumentCodec.decode(message.getDocument());
		} catch (Exception e) {
			fireError("The host sent an invalid OpenRocket document", e);
			return;
		}
		byte[] proposedOfflineSnapshot = message.getType() == LiveWireMessage.Type.WELCOME
				? offlineSnapshot : null;
		SwingUtilities.invokeLater(() -> {
			if (message.getType() == LiveWireMessage.Type.WELCOME) {
				roomName = message.getText();
				try {
					editPolicy = LiveProjectLink.EditPolicy.valueOf(message.getSettings());
				} catch (IllegalArgumentException | NullPointerException ignored) {
					editPolicy = LiveProjectLink.EditPolicy.ALLOW_OFFLINE_BRANCHES;
				}
			}
			applyingRemote = true;
			try {
				document.applyLiveSnapshot(decoded);
			} finally {
				applyingRemote = false;
			}
			revision = message.getRevision();
			connected = true;
			lastAcceptedSnapshot = message.getDocument().clone();
			for (LiveSessionEvent event : message.getHistory()) {
				recordEvent(event);
			}
			if (message.getEvent() != null) {
				recordEvent(message.getEvent());
			}
			scheduleAutosave(message.getDocument(), revision);
			fireStateChanged("Live at revision " + revision);
			if (message.getType() == LiveWireMessage.Type.WELCOME && proposedOfflineSnapshot != null) {
				sendToHost(LiveWireMessage.offlineProposal(invite.getSessionId(), offlineBaseRevision,
						participantId, participantName, proposedOfflineSnapshot));
				fireStateChanged("Connected — waiting for the host to review your offline branch");
			} else if (message.getType() == LiveWireMessage.Type.DOCUMENT_UPDATE) {
				offlineSnapshot = null;
				offlineBaseRevision = 0;
			}
		});
	}

	public void sendChat(String text) {
		if (text == null || text.isBlank() || !connected) {
			return;
		}
		String normalized = normalizeChat(text);
		LiveSessionEvent event = role == Role.HOST
				? createChatEvent(participantId, participantName, normalized) : null;
		LiveWireMessage message = LiveWireMessage.chat(invite.getSessionId(), participantId, participantName,
				normalized, event);
		if (role == Role.HOST) {
			notifyTransient(message);
			broadcastTransient(message, null);
		} else {
			sendToHost(message);
		}
	}

	public void sendPresence(String surfaceId) {
		if (surfaceId == null || !connected) {
			return;
		}
		LiveWireMessage message = LiveWireMessage.presence(invite.getSessionId(), participantId,
				participantName, surfaceId);
		localSurfaceId = surfaceId;
		notifyTransient(message);
		if (role == Role.HOST) {
			broadcastTransient(message, null);
		} else {
			sendToHost(message);
		}
	}

	public void sendCursor(String surfaceId, double x, double y) {
		if (surfaceId == null || !connected) {
			return;
		}
		LiveWireMessage message = LiveWireMessage.cursor(invite.getSessionId(), participantId,
				participantName, surfaceId, x, y);
		if (role == Role.HOST) {
			broadcastTransient(message, null);
		} else {
			sendToHost(message);
		}
	}

	public boolean removeParticipant(String selectedParticipantId, boolean ban) {
		if (role != Role.HOST || selectedParticipantId == null
				|| selectedParticipantId.equals(participantId)) {
			return false;
		}
		Peer selected = null;
		for (Peer peer : peers) {
			if (selectedParticipantId.equals(peer.participantId)) {
				selected = peer;
				break;
			}
		}
		if (selected == null) {
			return false;
		}

		if (ban) {
			bannedParticipantIds.add(selected.participantId);
		}
		selected.removalRecorded = true;
		long eventRevision = nextRevision();
		LiveSessionEvent.Type type = ban ? LiveSessionEvent.Type.PARTICIPANT_BANNED
				: LiveSessionEvent.Type.PARTICIPANT_KICKED;
		String action = ban ? " banned " : " removed ";
		LiveSessionEvent event = LiveSessionEvent.create(invite.getSessionId(), eventRevision,
				participantId, participantName, type, "participant", selected.participantId,
				participantName + action + selected.participantName, selected.participantName, null);
		recordEvent(event);
		LiveWireMessage eventMessage = LiveWireMessage.event(invite.getSessionId(), eventRevision, event);
		broadcastTransient(eventMessage, selected);
		Peer participant = selected;
		networkExecutor.execute(() -> {
			try {
				participant.connection.send(eventMessage);
				participant.connection.send(LiveWireMessage.error(
						ban ? "The host banned you from this Live room"
								: "The host removed you from this Live room"));
			} catch (IOException ignored) {
			} finally {
				closeQuietly(participant.connection);
			}
		});
		return true;
	}

	private LiveSessionEvent createChatEvent(String senderId, String senderName, String text) {
		return LiveSessionEvent.create(invite.getSessionId(), revision, senderId, senderName,
				LiveSessionEvent.Type.CHAT_MESSAGE, "room", invite.getSessionId(),
				senderName + " sent a message", null, text);
	}

	private static String normalizeChat(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String normalized = text.trim();
		return normalized.length() > MAX_CHAT_LENGTH ? normalized.substring(0, MAX_CHAT_LENGTH) : normalized;
	}

	private void sendKnownPresence(Peer joiningPeer) throws IOException {
		if (localSurfaceId != null) {
			joiningPeer.connection.send(LiveWireMessage.presence(invite.getSessionId(), participantId,
					participantName, localSurfaceId));
		}
		for (Peer peer : peers) {
			if (peer != joiningPeer && peer.surfaceId != null) {
				joiningPeer.connection.send(LiveWireMessage.presence(invite.getSessionId(), peer.participantId,
						peer.participantName, peer.surfaceId));
			}
		}
	}

	private void participantDisconnected(Peer peer) {
		if (!peers.remove(peer)) {
			return;
		}
		if (role != Role.HOST || invite == null) {
			return;
		}
		LiveWireMessage presence = LiveWireMessage.presence(invite.getSessionId(), peer.participantId,
				peer.participantName, null);
		notifyTransient(presence);
		broadcastTransient(presence, null);
		if (peer.removalRecorded) {
			return;
		}
		long eventRevision = nextRevision();
		LiveSessionEvent event = LiveSessionEvent.create(invite.getSessionId(), eventRevision,
				peer.participantId, peer.participantName, LiveSessionEvent.Type.PARTICIPANT_LEFT,
				"participant", peer.participantId, peer.participantName + " left", null, null);
		recordEvent(event);
		broadcastTransient(LiveWireMessage.event(invite.getSessionId(), eventRevision, event), null);
	}

	private void sendToHost(LiveWireMessage message) {
		LiveSecureConnection connection = hostConnection;
		if (connection != null) {
			networkExecutor.execute(() -> {
				try {
					connection.send(message);
				} catch (IOException e) {
					closeQuietly(connection);
				}
			});
		}
	}

	private void broadcastTransient(LiveWireMessage message, Peer excluded) {
		for (Peer peer : peers) {
			if (peer != excluded) {
				networkExecutor.execute(() -> send(peer, message));
			}
		}
	}

	private void notifyTransient(LiveWireMessage message) {
		if (message.getType() == LiveWireMessage.Type.CHAT && message.getEvent() != null) {
			recordEvent(message.getEvent());
			return;
		}
		SwingUtilities.invokeLater(() -> {
			for (Listener liveListener : listeners) {
				if (message.getType() == LiveWireMessage.Type.CHAT) {
					liveListener.chatReceived(message.getParticipantId(), message.getParticipantName(),
							message.getTimestamp(), message.getText());
				} else if (message.getType() == LiveWireMessage.Type.PRESENCE) {
					liveListener.presenceChanged(message.getParticipantId(), message.getParticipantName(),
							message.getSurfaceId());
				} else if (message.getType() == LiveWireMessage.Type.CURSOR) {
					liveListener.cursorMoved(message.getParticipantId(), message.getParticipantName(),
							message.getSurfaceId(), message.getX(), message.getY());
				}
			}
		});
	}

	private void onLocalDocumentChanged(DocumentChangeEvent event) {
		if (closed || role == Role.IDLE || applyingRemote) {
			return;
		}
		latestChange = event;
		if (pendingChange != null) {
			pendingChange.cancel(false);
		}
		pendingChange = scheduler.schedule(() -> SwingUtilities.invokeLater(this::publishLocalChange),
				CHANGE_DELAY.toMillis(), TimeUnit.MILLISECONDS);
	}

	private void publishLocalChange() {
		if (closed || role == Role.IDLE || applyingRemote) {
			return;
		}
		try {
			byte[] snapshot = LiveDocumentCodec.encode(document);
			if (role == Role.HOST) {
				long acceptedRevision = nextRevision();
				LiveSessionEvent event = describeChange(latestChange, acceptedRevision);
				recordEvent(event);
				publishSnapshot(snapshot, event);
			} else if (connected && hostConnection != null) {
				LiveWireMessage proposal = LiveWireMessage.proposal(invite.getSessionId(), revision, participantId,
						participantName, snapshot);
				sendToHost(proposal);
			} else if (editPolicy == LiveProjectLink.EditPolicy.ALLOW_OFFLINE_BRANCHES) {
				captureOfflineBranch(snapshot);
			} else {
				restoreAcceptedSnapshot();
			}
		} catch (Exception e) {
			fireError("Could not publish the latest OpenRocket Live change", e);
		}
	}

	private void captureOfflineBranch(byte[] snapshot) throws IOException {
		if (offlineSnapshot == null) {
			offlineBaseRevision = revision;
		}
		offlineSnapshot = snapshot;
		offlineBranchFile = offlineBranchPath(document.getFile());
		writeSnapshotAtomically(offlineBranchFile.toFile(), snapshot);
		fireStateChanged("Offline branch saved to “" + offlineBranchFile.getFileName() + "”");
	}

	private void restoreAcceptedSnapshot() {
		byte[] accepted = lastAcceptedSnapshot;
		if (accepted == null) {
			fireError("This room requires a connection before you can edit the design", null);
			return;
		}
		try {
			OpenRocketDocument decoded = LiveDocumentCodec.decode(accepted);
			applyingRemote = true;
			try {
				document.applyLiveSnapshot(decoded);
			} finally {
				applyingRemote = false;
			}
			scheduleAutosave(accepted, revision);
			fireStateChanged("Edit reverted — this room requires a connection");
		} catch (Exception e) {
			fireError("Could not restore the last connected design", e);
		}
	}

	private static Path offlineBranchPath(File designFile) {
		Path designPath = designFile.toPath().toAbsolutePath();
		String name = designPath.getFileName().toString();
		String lower = name.toLowerCase();
		if (lower.endsWith(".ork.gz")) {
			name = name.substring(0, name.length() - 7);
		} else if (lower.endsWith(".ork")) {
			name = name.substring(0, name.length() - 4);
		}
		return designPath.resolveSibling(name + "-offline-branch.ork");
	}

	private LiveSessionEvent describeChange(DocumentChangeEvent change, long eventRevision) {
		Object source = change == null ? document : change.getSource();
		String targetType = "document";
		String targetId = document.getRocket().getID().toString();
		String summary = participantName + " updated the design";
		if (source instanceof RocketComponent component) {
			targetType = "component";
			targetId = component.getID().toString();
			summary = participantName + " changed " + component.getName();
		} else if (source instanceof Simulation simulation) {
			targetType = "simulation";
			targetId = simulation.getId().key.toString();
			summary = participantName + " changed " + simulation.getName();
		}
		return LiveSessionEvent.create(invite.getSessionId(), eventRevision, participantId, participantName,
				LiveSessionEvent.Type.DOCUMENT_CHANGED, targetType, targetId, summary, null, null);
	}

	private void publishSnapshot(byte[] snapshot, LiveSessionEvent event) {
		LiveWireMessage update = LiveWireMessage.update(invite.getSessionId(), revision, event, snapshot);
		for (Peer peer : peers) {
			networkExecutor.execute(() -> send(peer, update));
		}
		scheduleAutosave(snapshot, revision);
	}

	private void sendCurrentDocument(Peer peer, String errorMessage) {
		try {
			peer.connection.send(LiveWireMessage.error(errorMessage));
			byte[] snapshot = encodeOnEventThread();
			peer.connection.send(LiveWireMessage.update(invite.getSessionId(), revision, null, snapshot));
		} catch (IOException e) {
			closeQuietly(peer.connection);
		}
	}

	private void send(Peer peer, LiveWireMessage message) {
		try {
			peer.connection.send(message);
		} catch (IOException e) {
			participantDisconnected(peer);
			closeQuietly(peer.connection);
		}
	}

	private byte[] encodeOnEventThread() throws IOException {
		if (SwingUtilities.isEventDispatchThread()) {
			return encodeDocument();
		}
		final byte[][] result = new byte[1][];
		final IOException[] failure = new IOException[1];
		try {
			SwingUtilities.invokeAndWait(() -> {
				try {
					result[0] = encodeDocument();
				} catch (IOException e) {
					failure[0] = e;
				}
			});
		} catch (Exception e) {
			throw new IOException("Could not snapshot the OpenRocket document", e);
		}
		if (failure[0] != null) {
			throw failure[0];
		}
		return result[0];
	}

	private byte[] encodeDocument() throws IOException {
		try {
			return LiveDocumentCodec.encode(document);
		} catch (Exception e) {
			throw new IOException("Could not encode the OpenRocket document", e);
		}
	}

	private synchronized long nextRevision() {
		return ++revision;
	}

	private synchronized void recordEvent(LiveSessionEvent event) {
		if (!loggedEventIds.add(event.getEventId())) {
			return;
		}
		history.add(event);
		try {
			sessionLog.append(event);
		} catch (IOException e) {
			fireError("Could not append to the local OpenRocket Live changelog", e);
		}
		fireEventReceived(event);
	}

	private void replayHistory() {
		for (LiveSessionEvent event : getHistory()) {
			fireEventReceived(event);
		}
	}

	private void fireEventReceived(LiveSessionEvent event) {
		SwingUtilities.invokeLater(() -> {
			for (Listener liveListener : listeners) {
				liveListener.eventReceived(event);
			}
		});
	}

	private synchronized void scheduleAutosave(byte[] snapshot, long snapshotRevision) {
		if (pendingSave != null) {
			pendingSave.cancel(false);
		}
		pendingSnapshot = snapshot;
		pendingSnapshotRevision = snapshotRevision;
		pendingSave = scheduler.schedule(() -> {
			try {
				writeSnapshotAtomically(document.getFile(), snapshot);
				if (revision == snapshotRevision) {
					SwingUtilities.invokeLater(() -> document.setSaved(true));
				}
				synchronized (LiveSessionManager.this) {
					if (pendingSnapshot == snapshot) {
						pendingSnapshot = null;
						pendingSave = null;
					}
				}
			} catch (IOException e) {
				fireError("Could not autosave the local OpenRocket Live design", e);
			}
		}, SAVE_DELAY.toMillis(), TimeUnit.MILLISECONDS);
	}

	private static void writeSnapshotAtomically(File file, byte[] snapshot) throws IOException {
		Path destination = file.toPath().toAbsolutePath();
		Path parent = destination.getParent();
		if (parent == null) {
			throw new IOException("The OpenRocket Live file has no parent directory: " + destination);
		}
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, ".openrocket-live-", ".tmp");
		try {
			Files.write(temporary, snapshot);
			try {
				Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private void fireStateChanged(String status) {
		SwingUtilities.invokeLater(() -> {
			for (Listener liveListener : listeners) {
				liveListener.stateChanged(role, status);
			}
		});
	}

	private void fireError(String message, Throwable cause) {
		log.warn(message, cause);
		SwingUtilities.invokeLater(() -> {
			for (Listener liveListener : listeners) {
				liveListener.error(message, cause);
			}
		});
	}

	private static String findBestLocalAddress() {
		try {
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces.hasMoreElements()) {
				NetworkInterface network = interfaces.nextElement();
				if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
					continue;
				}
				Enumeration<InetAddress> addresses = network.getInetAddresses();
				while (addresses.hasMoreElements()) {
					InetAddress address = addresses.nextElement();
					if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
						return address.getHostAddress();
					}
				}
			}
		} catch (IOException e) {
			log.debug("Could not determine a LAN address", e);
		}
		return InetAddress.getLoopbackAddress().getHostAddress();
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		leaveSession();
		closed = true;
		scheduler.shutdownNow();
		networkExecutor.shutdownNow();
	}

	public synchronized void leaveSession() {
		if (role == Role.IDLE) {
			return;
		}
		role = Role.IDLE;
		connected = false;
		document.removeDocumentChangeListener(documentListener);
		if (pendingChange != null) {
			pendingChange.cancel(false);
		}
		flushPendingAutosave();
		closeQuietly(hostConnection);
		for (Peer peer : peers) {
			closeQuietly(peer.connection);
		}
		peers.clear();
		closeQuietly(serverSocket);
		closeQuietly(sessionLog);
		hostConnection = null;
		serverSocket = null;
		sessionLog = null;
		invite = null;
		history.clear();
		loggedEventIds.clear();
		bannedParticipantIds.clear();
		revision = 0;
		roomName = null;
		localSurfaceId = null;
		reconnectScheduled = false;
		retryInitialConnection = false;
		everConnected = false;
		removedByHost = false;
		lastAcceptedSnapshot = null;
		offlineSnapshot = null;
		offlineBaseRevision = 0;
		offlineBranchFile = null;
		fireStateChanged("Not in a Live session");
	}

	private void flushPendingAutosave() {
		if (pendingSave != null) {
			pendingSave.cancel(false);
		}
		if (pendingSnapshot != null && document.getFile() != null) {
			try {
				writeSnapshotAtomically(document.getFile(), pendingSnapshot);
				if (revision == pendingSnapshotRevision) {
					document.setSaved(true);
				}
			} catch (IOException e) {
				fireError("Could not finish the local OpenRocket Live autosave", e);
			}
		}
		pendingSave = null;
		pendingSnapshot = null;
	}

	private static void closeQuietly(Closeable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		} catch (IOException ignored) {
		}
	}

	private static final class Peer {
		private final String participantId;
		private final String participantName;
		private final LiveSecureConnection connection;
		private volatile String surfaceId;
		private volatile boolean removalRecorded;

		private Peer(String participantId, String participantName, LiveSecureConnection connection) {
			this.participantId = participantId;
			this.participantName = participantName;
			this.connection = connection;
		}
	}
}
