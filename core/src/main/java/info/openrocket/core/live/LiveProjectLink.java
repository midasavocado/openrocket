package info.openrocket.core.live;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

import com.google.gson.Gson;

/** Local sidecar that links one participant-owned .ork file to a reusable Live room. */
public final class LiveProjectLink {
	public enum LocalRole {
		HOST,
		PARTICIPANT
	}

	public enum EditPolicy {
		ALLOW_OFFLINE_BRANCHES,
		REQUIRE_CONNECTION
	}

	private static final Gson GSON = new Gson();

	private final int version;
	private final String roomId;
	private final String roomName;
	private final String invite;
	private final String displayName;
	private final String clientId;
	private final LocalRole localRole;
	private final EditPolicy editPolicy;
	private final boolean autoOpen;
	private final long lastRevision;
	private final String updatedAt;

	public LiveProjectLink(String roomId, String roomName, String invite, String displayName, String clientId,
			LocalRole localRole, EditPolicy editPolicy, boolean autoOpen, long lastRevision) {
		this.version = 1;
		this.roomId = roomId;
		this.roomName = roomName;
		this.invite = invite;
		this.displayName = displayName;
		this.clientId = clientId == null ? UUID.randomUUID().toString() : clientId;
		this.localRole = localRole;
		this.editPolicy = editPolicy;
		this.autoOpen = autoOpen;
		this.lastRevision = lastRevision;
		this.updatedAt = Instant.now().toString();
	}

	public LiveProjectLink withSettings(EditPolicy policy, boolean shouldAutoOpen) {
		return new LiveProjectLink(roomId, roomName, invite, displayName, clientId, localRole,
				policy, shouldAutoOpen, lastRevision);
	}

	public LiveProjectLink withConnection(String encodedInvite, long revision) {
		return new LiveProjectLink(roomId, roomName, encodedInvite, displayName, clientId, localRole,
				editPolicy, autoOpen, revision);
	}

	public static Path pathForDesign(File designFile) {
		String name = designFile.getName();
		String lower = name.toLowerCase();
		if (lower.endsWith(".ork.gz")) {
			name = name.substring(0, name.length() - 7);
		} else if (lower.endsWith(".ork")) {
			name = name.substring(0, name.length() - 4);
		}
		return designFile.toPath().toAbsolutePath().resolveSibling(name + ".orklive");
	}

	public static LiveProjectLink read(File designFile) throws IOException {
		Path path = pathForDesign(designFile);
		if (!Files.exists(path)) {
			return null;
		}
		return GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), LiveProjectLink.class);
	}

	public void write(File designFile) throws IOException {
		Path destination = pathForDesign(designFile);
		Path parent = destination.getParent();
		if (parent == null) {
			throw new IOException("The OpenRocket Live link has no parent directory: " + destination);
		}
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, ".openrocket-live-link-", ".tmp");
		try {
			Files.writeString(temporary, GSON.toJson(this), StandardCharsets.UTF_8);
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

	public int getVersion() {
		return version;
	}

	public String getRoomId() {
		return roomId;
	}

	public String getRoomName() {
		return roomName;
	}

	public String getInvite() {
		return invite;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getClientId() {
		return clientId;
	}

	public LocalRole getLocalRole() {
		return localRole;
	}

	public EditPolicy getEditPolicy() {
		return editPolicy;
	}

	public boolean isAutoOpen() {
		return autoOpen;
	}

	public long getLastRevision() {
		return lastRevision;
	}

	public String getUpdatedAt() {
		return updatedAt;
	}
}
