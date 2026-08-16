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
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import info.openrocket.core.live.LiveSecureConnection;
import info.openrocket.core.live.LiveSessionEvent;
import info.openrocket.core.live.LiveSessionLog;
import info.openrocket.core.live.LiveWireMessage;
import info.openrocket.core.rocketcomponent.RocketComponent;

/** Coordinates a direct host or participant connection for one OpenRocket document. */
public final class LiveSessionManager implements Closeable {
	public enum Role {
		IDLE,
		HOST,
		PARTICIPANT
	}

	public interface Listener {
		void stateChanged(Role role, String status);

		void eventReceived(LiveSessionEvent event);

		void error(String message, Throwable cause);

		default void chatReceived(String participantId, String participantName, String timestamp, String text) {
		}

		default void presenceChanged(String participantId, String participantName, String surfaceId) {
		}

		default void cursorMoved(String participantId, String participantName, String surfaceId,
				double x, double y) {
		}
	}

	private static final Logger log = LoggerFactory.getLogger(LiveSessionManager.class);
	private static final Duration CHANGE_DELAY = Duration.ofMillis(100);
	private static final Duration SAVE_DELAY = Duration.ofMillis(500);
	private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
	private static final int MAX_CHAT_LENGTH = 4_000;

	private final OpenRocketDocument document;
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
	private final DocumentChangeListener documentListener = new DocumentChangeListener() {
		@Override
		public void documentChanged(DocumentChangeEvent event) {
			onLocalDocumentChanged(event);
		}
	};

	private volatile Role role = Role.IDLE;
	private volatile long revision;
	private volatile boolean applyingRemote;
	private volatile boolean closed;
	private String participantId;
	private String participantName;
	private LiveInvite invite;
	private LiveSessionLog sessionLog;
	private ServerSocket serverSocket;
	private LiveSecureConnection hostConnection;
	private ScheduledFuture<?> pendingChange;
	private ScheduledFuture<?> pendingSave;
	private byte[] pendingSnapshot;
	private long pendingSnapshotRevision;
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
		ensureIdleAndSaved();
		participantId = UUID.randomUUID().toString();
		participantName = name;
		serverSocket = new ServerSocket(0);
		serverSocket.setReuseAddress(true);
		invite = LiveInvite.create(findBestLocalAddress(), serverSocket.getLocalPort());
		sessionLog = new LiveSessionLog(LiveSessionLog.pathForDesign(document.getFile()));
		role = Role.HOST;
		document.addDocumentChangeListener(documentListener);

		LiveSessionEvent event = LiveSessionEvent.create(invite.getSessionId(), revision, participantId,
				participantName, LiveSessionEvent.Type.SESSION_STARTED, "document",
				document.getRocket().getID().toString(), "Started an OpenRocket Live session", null, null);
		recordEvent(event);
		networkExecutor.execute(this::acceptLoop);
		fireStateChanged("Hosting on " + invite.getHost() + ":" + invite.getPort());
		return invite;
	}

	public synchronized void join(LiveInvite liveInvite, String name, File localFile) throws IOException {
		if (role != Role.IDLE) {
			throw new IllegalStateException("This document is already in an OpenRocket Live session");
		}
		participantId = UUID.randomUUID().toString();
		participantName = name;
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
			socket.setSoTimeout(0);
			peer = new Peer(hello.getParticipantId(), hello.getParticipantName(), connection);
			peers.add(peer);
			long joinRevision = nextRevision();
			LiveSessionEvent joined = LiveSessionEvent.create(invite.getSessionId(), joinRevision,
					hello.getParticipantId(), hello.getParticipantName(), LiveSessionEvent.Type.PARTICIPANT_JOINED,
					"session", invite.getSessionId(), hello.getParticipantName() + " joined", null, null);
			recordEvent(joined);
			byte[] snapshot = encodeOnEventThread();
			connection.send(LiveWireMessage.welcome(invite.getSessionId(), revision, snapshot, getHistory()));
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
				peers.remove(peer);
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

	private void connectToHost() {
		try {
			Socket socket = new Socket();
			socket.connect(new InetSocketAddress(invite.getHost(), invite.getPort()), CONNECT_TIMEOUT_MILLIS);
			socket.setTcpNoDelay(true);
			socket.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
			hostConnection = LiveSecureConnection.connect(socket, invite.getSecret());
			hostConnection.send(LiveWireMessage.hello(invite.getSessionId(), participantId, participantName));

			LiveWireMessage message;
			message = hostConnection.receive();
			socket.setSoTimeout(0);
			if (message != null) {
				handleHostMessage(message);
			}
			while ((message = hostConnection.receive()) != null && role == Role.PARTICIPANT) {
				handleHostMessage(message);
			}
		} catch (IOException e) {
			if (!closed && role == Role.PARTICIPANT) {
				fireError("OpenRocket Live connection ended", e);
				fireStateChanged("Disconnected; your local files are still available");
			}
		}
	}

	private void handleParticipantMessage(Peer peer, LiveWireMessage incoming) {
		LiveWireMessage relayed;
		if (incoming.getType() == LiveWireMessage.Type.CHAT) {
			relayed = LiveWireMessage.chat(invite.getSessionId(), peer.participantId, peer.participantName,
					incoming.getText());
			notifyTransient(relayed);
		} else if (incoming.getType() == LiveWireMessage.Type.PRESENCE) {
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
			fireError(message.getText(), null);
			return;
		}
		if (message.getType() == LiveWireMessage.Type.CHAT
				|| message.getType() == LiveWireMessage.Type.PRESENCE
				|| message.getType() == LiveWireMessage.Type.CURSOR) {
			notifyTransient(message);
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
		SwingUtilities.invokeLater(() -> {
			applyingRemote = true;
			try {
				document.applyLiveSnapshot(decoded);
			} finally {
				applyingRemote = false;
			}
			revision = message.getRevision();
			for (LiveSessionEvent event : message.getHistory()) {
				recordEvent(event);
			}
			if (message.getEvent() != null) {
				recordEvent(message.getEvent());
			}
			scheduleAutosave(message.getDocument(), revision);
			fireStateChanged("Live at revision " + revision);
		});
	}

	public void sendChat(String text) {
		if (text == null || text.isBlank() || role == Role.IDLE) {
			return;
		}
		String normalized = text.trim();
		if (normalized.length() > MAX_CHAT_LENGTH) {
			normalized = normalized.substring(0, MAX_CHAT_LENGTH);
		}
		LiveWireMessage message = LiveWireMessage.chat(invite.getSessionId(), participantId, participantName,
				normalized);
		if (role == Role.HOST) {
			notifyTransient(message);
			broadcastTransient(message, null);
		} else {
			sendToHost(message);
		}
	}

	public void sendPresence(String surfaceId) {
		if (surfaceId == null || role == Role.IDLE) {
			return;
		}
		LiveWireMessage message = LiveWireMessage.presence(invite.getSessionId(), participantId,
				participantName, surfaceId);
		if (role == Role.HOST) {
			broadcastTransient(message, null);
		} else {
			sendToHost(message);
		}
	}

	public void sendCursor(String surfaceId, double x, double y) {
		if (surfaceId == null || role == Role.IDLE) {
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

	private void sendToHost(LiveWireMessage message) {
		LiveSecureConnection connection = hostConnection;
		if (connection != null) {
			networkExecutor.execute(() -> {
				try {
					connection.send(message);
				} catch (IOException e) {
					fireError("Could not send an OpenRocket Live message", e);
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
			} else if (hostConnection != null) {
				LiveWireMessage proposal = LiveWireMessage.proposal(invite.getSessionId(), revision, participantId,
						participantName, snapshot);
				sendToHost(proposal);
			}
		} catch (Exception e) {
			fireError("Could not publish the latest OpenRocket Live change", e);
		}
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
			peers.remove(peer);
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
		if (parent != null) {
			Files.createDirectories(parent);
		}
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
		revision = 0;
		role = Role.IDLE;
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

		private Peer(String participantId, String participantName, LiveSecureConnection connection) {
			this.participantId = participantId;
			this.participantName = participantName;
			this.connection = connection;
		}
	}
}
