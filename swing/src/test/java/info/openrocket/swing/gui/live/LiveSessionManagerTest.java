package info.openrocket.swing.gui.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.OpenRocketDocumentFactory;
import info.openrocket.core.file.GeneralRocketSaver;
import info.openrocket.core.live.LiveInvite;
import info.openrocket.core.live.LiveDocumentCodec;
import info.openrocket.core.live.LiveProjectLink;
import info.openrocket.core.live.LiveSessionEvent;
import info.openrocket.core.live.LiveSessionLog;
import info.openrocket.swing.util.BaseTestCase;

class LiveSessionManagerTest extends BaseTestCase {
	@TempDir
	Path temporaryDirectory;

	@Test
	void hostAndParticipantMaintainIndependentLiveFilesAndLogs() throws Exception {
		OpenRocketDocument hostDocument = OpenRocketDocumentFactory.createNewRocket();
		File hostFile = temporaryDirectory.resolve("host.ork").toFile();
		hostDocument.setFile(hostFile);
		new GeneralRocketSaver().save(hostFile, hostDocument);

		OpenRocketDocument participantDocument = OpenRocketDocumentFactory.createNewRocket();
		File participantFile = temporaryDirectory.resolve("participant.ork").toFile();
		RecordingListener hostListener = new RecordingListener();
		RecordingListener participantListener = new RecordingListener();
		LiveSessionManager host = new LiveSessionManager(hostDocument, hostListener);
		LiveSessionManager participant = new LiveSessionManager(participantDocument, participantListener);
		try {
			LiveInvite invite = host.host("Host");
			participant.join(invite, "Friend", participantFile);
			assertTrue(participantListener.live.await(8, TimeUnit.SECONDS));
			assertTrue(host.isConnected());
			assertTrue(participant.isConnected());

			SwingUtilities.invokeAndWait(() -> hostDocument.getRocket().setName("Live Rocket"));
			await(() -> "Live Rocket".equals(participantDocument.getRocket().getName()), 8);
			await(() -> participantFile.isFile() && participantFile.length() > 100, 8);

			SwingUtilities.invokeAndWait(() -> participantDocument.getRocket().setName("Friend's Rocket"));
			await(() -> "Friend's Rocket".equals(hostDocument.getRocket().getName()), 8);
			await(() -> {
				try {
					return "Friend's Rocket".equals(
							LiveDocumentCodec.decode(Files.readAllBytes(hostFile.toPath())).getRocket().getName())
							&& "Friend's Rocket".equals(
							LiveDocumentCodec.decode(Files.readAllBytes(participantFile.toPath())).getRocket().getName());
				} catch (Exception e) {
					return false;
				}
			}, 8);

			host.sendChat("Hello from the host");
			participant.sendChat("Hello from the friend");
			participant.sendPresence("main:2");
			participant.sendCursor("main:2", 0.25, 0.75);
			await(() -> participantListener.chatMessages.contains("Host: Hello from the host"), 8);
			await(() -> hostListener.chatMessages.contains("Friend: Hello from the friend"), 8);
			await(() -> hostListener.presenceMessages.contains("Friend:main:2"), 8);
			await(() -> hostListener.cursorMessages.contains("Friend:main:2:0.25:0.75"), 8);

			assertEquals("Friend's Rocket", hostDocument.getRocket().getName());
			assertEquals("Friend's Rocket", participantDocument.getRocket().getName());
			assertTrue(hostFile.isFile());
			assertTrue(participantFile.isFile());
			assertTrue(Files.exists(LiveSessionLog.pathForDesign(hostFile)));
			assertTrue(Files.exists(LiveSessionLog.pathForDesign(participantFile)));
			assertTrue(LiveSessionLog.read(LiveSessionLog.pathForDesign(hostFile)).size() >= 3);
			assertTrue(LiveSessionLog.read(LiveSessionLog.pathForDesign(participantFile)).size() >= 2);
		} finally {
			participant.close();
			host.close();
		}
	}

	@Test
	void failedJoinReturnsToIdleInsteadOfTrappingTheDocument() throws Exception {
		int unusedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			unusedPort = socket.getLocalPort();
		}
		LiveInvite unreachable = LiveInvite.create("127.0.0.1", unusedPort);
		OpenRocketDocument document = OpenRocketDocumentFactory.createNewRocket();
		RecordingListener listener = new RecordingListener();
		LiveSessionManager participant = new LiveSessionManager(document, listener);
		try {
			participant.join(unreachable, "Friend", temporaryDirectory.resolve("failed.ork").toFile());
			await(() -> participant.getRole() == LiveSessionManager.Role.IDLE, 8);
			assertFalse(participant.isConnected());
			assertTrue(listener.error.await(8, TimeUnit.SECONDS));
		} finally {
			participant.close();
		}
	}

	@Test
	void disconnectedEditsBecomeAnOfflineBranchThatTheHostCanAccept() throws Exception {
		OpenRocketDocument hostDocument = OpenRocketDocumentFactory.createNewRocket();
		File hostFile = temporaryDirectory.resolve("offline-host.ork").toFile();
		hostDocument.setFile(hostFile);
		new GeneralRocketSaver().save(hostFile, hostDocument);
		OpenRocketDocument participantDocument = OpenRocketDocumentFactory.createNewRocket();
		File participantFile = temporaryDirectory.resolve("offline-friend.ork").toFile();
		RecordingListener participantListener = new RecordingListener();
		LiveSessionManager originalHost = new LiveSessionManager(hostDocument, new RecordingListener());
		LiveSessionManager participant = new LiveSessionManager(participantDocument, participantListener);
		LiveSessionManager resumedHost = null;
		try {
			LiveInvite invite = originalHost.host("Host", "Workshop", null);
			participant.join(invite, "Friend", participantFile);
			assertTrue(participantListener.live.await(8, TimeUnit.SECONDS));

			originalHost.leaveSession();
			await(() -> !participant.isConnected()
					&& participant.getRole() == LiveSessionManager.Role.PARTICIPANT, 8);
			SwingUtilities.invokeAndWait(() -> participantDocument.getRocket().setName("Offline Rocket"));
			Path branchFile = temporaryDirectory.resolve("offline-friend-offline-branch.ork");
			await(() -> Files.exists(branchFile), 8);
			assertEquals("Offline Rocket",
					LiveDocumentCodec.decode(Files.readAllBytes(branchFile)).getRocket().getName());

			RecordingListener resumedHostListener = new RecordingListener(true);
			resumedHost = new LiveSessionManager(hostDocument, resumedHostListener);
			resumedHost.host("Host", "Workshop", invite.getSessionId(), invite,
					LiveProjectLink.EditPolicy.ALLOW_OFFLINE_BRANCHES);
			await(() -> "Offline Rocket".equals(hostDocument.getRocket().getName()), 15);
			assertTrue(resumedHostListener.offlineProposal.await(8, TimeUnit.SECONDS));
			await(() -> participant.isConnected(), 8);
		} finally {
			participant.close();
			if (resumedHost != null) {
				resumedHost.close();
			}
			originalHost.close();
		}
	}

	@Test
	void hostCanBanAParticipantIdentityFromTheRoom() throws Exception {
		OpenRocketDocument hostDocument = OpenRocketDocumentFactory.createNewRocket();
		File hostFile = temporaryDirectory.resolve("ban-host.ork").toFile();
		hostDocument.setFile(hostFile);
		new GeneralRocketSaver().save(hostFile, hostDocument);
		OpenRocketDocument participantDocument = OpenRocketDocumentFactory.createNewRocket();
		RecordingListener participantListener = new RecordingListener();
		LiveSessionManager host = new LiveSessionManager(hostDocument, new RecordingListener());
		LiveSessionManager participant = new LiveSessionManager(participantDocument, participantListener);
		LiveSessionManager returningParticipant = null;
		try {
			LiveInvite invite = host.host("Host", "Ban Test", null);
			participant.join(invite, "Friend", temporaryDirectory.resolve("banned-friend.ork").toFile());
			assertTrue(participantListener.live.await(8, TimeUnit.SECONDS));
			String bannedId = participant.getParticipantId();

			assertTrue(host.removeParticipant(bannedId, true));
			await(() -> participant.getRole() == LiveSessionManager.Role.IDLE, 8);

			RecordingListener returningListener = new RecordingListener();
			returningParticipant = new LiveSessionManager(
					OpenRocketDocumentFactory.createNewRocket(), returningListener);
			returningParticipant.join(invite, "Friend",
					temporaryDirectory.resolve("banned-friend-return.ork").toFile(), bannedId,
					LiveProjectLink.EditPolicy.ALLOW_OFFLINE_BRANCHES, true);
			LiveSessionManager finalReturningParticipant = returningParticipant;
			await(() -> finalReturningParticipant.getRole() == LiveSessionManager.Role.IDLE, 8);
			assertTrue(returningListener.error.await(8, TimeUnit.SECONDS));
		} finally {
			if (returningParticipant != null) {
				returningParticipant.close();
			}
			participant.close();
			host.close();
		}
	}

	@Test
	void hostCanTransferTheRoomAndBecomeAParticipant() throws Exception {
		OpenRocketDocument firstHostDocument = OpenRocketDocumentFactory.createNewRocket();
		File firstHostFile = temporaryDirectory.resolve("first-host.ork").toFile();
		firstHostDocument.setFile(firstHostFile);
		new GeneralRocketSaver().save(firstHostFile, firstHostDocument);
		OpenRocketDocument nextHostDocument = OpenRocketDocumentFactory.createNewRocket();
		RecordingListener nextHostListener = new RecordingListener();
		LiveSessionManager firstHost = new LiveSessionManager(firstHostDocument, new RecordingListener());
		LiveSessionManager nextHost = new LiveSessionManager(nextHostDocument, nextHostListener);
		try {
			LiveInvite originalInvite = firstHost.host("First Host", "Handoff Room", null);
			nextHost.join(originalInvite, "Next Host", temporaryDirectory.resolve("next-host.ork").toFile());
			assertTrue(nextHostListener.live.await(8, TimeUnit.SECONDS));

			assertTrue(firstHost.transferHost(nextHost.getParticipantId()));
			await(() -> nextHost.getRole() == LiveSessionManager.Role.HOST
					&& firstHost.getRole() == LiveSessionManager.Role.PARTICIPANT
					&& firstHost.isConnected(), 12);
			assertEquals("Handoff Room", nextHost.getRoomName());
			assertEquals(originalInvite.getSessionId(), nextHost.getInvite().getSessionId());

			SwingUtilities.invokeAndWait(() -> firstHostDocument.getRocket().setName("Edited After Handoff"));
			await(() -> "Edited After Handoff".equals(nextHostDocument.getRocket().getName()), 8);
		} finally {
			nextHost.close();
			firstHost.close();
		}
	}

	private static void await(BooleanSupplier condition, int seconds) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			Thread.sleep(50);
		}
		assertTrue(condition.getAsBoolean());
	}

	private static final class RecordingListener implements LiveSessionManager.Listener {
		private final CountDownLatch live = new CountDownLatch(1);
		private final CountDownLatch error = new CountDownLatch(1);
		private final CountDownLatch offlineProposal = new CountDownLatch(1);
		private final boolean acceptOfflineBranches;
		private final CopyOnWriteArrayList<String> chatMessages = new CopyOnWriteArrayList<>();
		private final CopyOnWriteArrayList<String> presenceMessages = new CopyOnWriteArrayList<>();
		private final CopyOnWriteArrayList<String> cursorMessages = new CopyOnWriteArrayList<>();

		private RecordingListener() {
			this(false);
		}

		private RecordingListener(boolean acceptOfflineBranches) {
			this.acceptOfflineBranches = acceptOfflineBranches;
		}

		@Override
		public void stateChanged(LiveSessionManager.Role role, String status) {
			if (role == LiveSessionManager.Role.PARTICIPANT && status.startsWith("Live at revision")) {
				live.countDown();
			}
		}

		@Override
		public void eventReceived(LiveSessionEvent event) {
			if (event.getType() == LiveSessionEvent.Type.CHAT_MESSAGE) {
				chatMessages.add(event.getParticipantName() + ": " + event.getNewValue());
			}
		}

		@Override
		public void error(String message, Throwable cause) {
			error.countDown();
		}

		@Override
		public void chatReceived(String participantId, String participantName, String timestamp, String text) {
			chatMessages.add(participantName + ": " + text);
		}

		@Override
		public void presenceChanged(String participantId, String participantName, String surfaceId) {
			presenceMessages.add(participantName + ":" + surfaceId);
		}

		@Override
		public void cursorMoved(String participantId, String participantName, String surfaceId,
				double x, double y) {
			cursorMessages.add(participantName + ":" + surfaceId + ":" + x + ":" + y);
		}

		@Override
		public void offlineBranchProposed(String participantId, String participantName,
				Runnable accept, Runnable keepHostVersion) {
			offlineProposal.countDown();
			if (acceptOfflineBranches) {
				accept.run();
			} else {
				keepHostVersion.run();
			}
		}
	}
}
