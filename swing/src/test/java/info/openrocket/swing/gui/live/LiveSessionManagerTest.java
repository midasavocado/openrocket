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
		private final CopyOnWriteArrayList<String> chatMessages = new CopyOnWriteArrayList<>();
		private final CopyOnWriteArrayList<String> presenceMessages = new CopyOnWriteArrayList<>();
		private final CopyOnWriteArrayList<String> cursorMessages = new CopyOnWriteArrayList<>();

		@Override
		public void stateChanged(LiveSessionManager.Role role, String status) {
			if (role == LiveSessionManager.Role.PARTICIPANT && status.startsWith("Live at revision")) {
				live.countDown();
			}
		}

		@Override
		public void eventReceived(LiveSessionEvent event) {
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
	}
}
