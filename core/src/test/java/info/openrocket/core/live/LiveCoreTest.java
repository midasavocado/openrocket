package info.openrocket.core.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.OpenRocketDocumentFactory;
import info.openrocket.core.util.BaseTestCase;

class LiveCoreTest extends BaseTestCase {
	@TempDir
	Path temporaryDirectory;

	@Test
	void documentCodecRoundTripsAStandardOrk() throws Exception {
		OpenRocketDocument original = OpenRocketDocumentFactory.createNewRocket();
		original.getRocket().setName("Collaborative Rocket");

		byte[] encoded = LiveDocumentCodec.encode(original);
		OpenRocketDocument decoded = LiveDocumentCodec.decode(encoded);

		assertTrue(encoded.length > 100);
		assertEquals("Collaborative Rocket", decoded.getRocket().getName());
		assertEquals(original.getRocket().getID(), decoded.getRocket().getID());
	}

	@Test
	void logUsesOneRecoverableJsonRecordPerEvent() throws Exception {
		Path logPath = temporaryDirectory.resolve("rocket.orklog");
		LiveSessionEvent event = LiveSessionEvent.create("session", 7, "person", "Midas",
				LiveSessionEvent.Type.DOCUMENT_CHANGED, "component", "component-id",
				"Midas changed Body Tube", "1", "2");
		try (LiveSessionLog log = new LiveSessionLog(logPath)) {
			log.append(event);
		}
		Files.writeString(logPath, "{truncated", java.nio.file.StandardOpenOption.APPEND);

		List<LiveSessionEvent> events = LiveSessionLog.read(logPath);
		assertEquals(1, events.size());
		assertEquals(event.getEventId(), events.get(0).getEventId());
		assertEquals(7, events.get(0).getRevision());
	}

	@Test
	void inviteSecretAuthenticatesAndEncryptsMessages() throws Exception {
		LiveInvite invite;
		try (ServerSocket server = new ServerSocket(0)) {
			invite = LiveInvite.create("127.0.0.1", server.getLocalPort());
			CompletableFuture<LiveWireMessage> received = CompletableFuture.supplyAsync(() -> {
				try (Socket socket = server.accept();
						LiveSecureConnection connection = LiveSecureConnection.accept(socket, invite.getSecret())) {
					LiveWireMessage message = connection.receive();
					connection.send(LiveWireMessage.error("hello back"));
					return message;
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});

			try (Socket socket = new Socket(invite.getHost(), invite.getPort());
					LiveSecureConnection connection = LiveSecureConnection.connect(socket, invite.getSecret())) {
				connection.send(LiveWireMessage.hello(invite.getSessionId(), "person", "Midas"));
				LiveWireMessage response = connection.receive();
				assertNotNull(response);
				assertEquals("hello back", response.getText());
			}

			LiveWireMessage message = received.get(5, TimeUnit.SECONDS);
			assertEquals(LiveWireMessage.Type.HELLO, message.getType());
			assertEquals("Midas", message.getParticipantName());
		}
	}

	@Test
	void projectLinkRoundTripsBesideTheDesign() throws Exception {
		File design = temporaryDirectory.resolve("shared-design.ork").toFile();
		LiveProjectLink link = new LiveProjectLink("room-id", "Launch Team", "invite-value", "Midas",
				"client-id", LiveProjectLink.LocalRole.PARTICIPANT,
				LiveProjectLink.EditPolicy.REQUIRE_CONNECTION, true, 42);

		link.write(design);
		LiveProjectLink restored = LiveProjectLink.read(design);

		assertNotNull(restored);
		assertEquals("room-id", restored.getRoomId());
		assertEquals("Launch Team", restored.getRoomName());
		assertEquals("client-id", restored.getClientId());
		assertEquals(LiveProjectLink.EditPolicy.REQUIRE_CONNECTION, restored.getEditPolicy());
		assertEquals(42, restored.getLastRevision());
		assertTrue(Files.exists(LiveProjectLink.pathForDesign(design)));
	}
}
