package info.openrocket.core.live;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One host-ordered event in an OpenRocket Live session.
 */
public final class LiveSessionEvent {
	public enum Type {
		SESSION_STARTED,
		PARTICIPANT_JOINED,
		PARTICIPANT_LEFT,
		PARTICIPANT_KICKED,
		PARTICIPANT_BANNED,
		HOST_TRANSFERRED,
		DOCUMENT_CHANGED,
		CHAT_MESSAGE
	}

	private final String sessionId;
	private final long revision;
	private final String eventId;
	private final String participantId;
	private final String participantName;
	private final String timestamp;
	private final Type type;
	private final String targetType;
	private final String targetId;
	private final String summary;
	private final String oldValue;
	private final String newValue;

	public LiveSessionEvent(String sessionId, long revision, String eventId, String participantId,
			String participantName, String timestamp, Type type, String targetType, String targetId,
			String summary, String oldValue, String newValue) {
		this.sessionId = Objects.requireNonNull(sessionId);
		this.revision = revision;
		this.eventId = Objects.requireNonNull(eventId);
		this.participantId = Objects.requireNonNull(participantId);
		this.participantName = Objects.requireNonNull(participantName);
		this.timestamp = Objects.requireNonNull(timestamp);
		this.type = Objects.requireNonNull(type);
		this.targetType = targetType;
		this.targetId = targetId;
		this.summary = Objects.requireNonNull(summary);
		this.oldValue = oldValue;
		this.newValue = newValue;
	}

	public static LiveSessionEvent create(String sessionId, long revision, String participantId,
			String participantName, Type type, String targetType, String targetId, String summary,
			String oldValue, String newValue) {
		return new LiveSessionEvent(sessionId, revision, UUID.randomUUID().toString(), participantId,
				participantName, Instant.now().toString(), type, targetType, targetId, summary, oldValue, newValue);
	}

	public String getSessionId() {
		return sessionId;
	}

	public long getRevision() {
		return revision;
	}

	public String getEventId() {
		return eventId;
	}

	public String getParticipantId() {
		return participantId;
	}

	public String getParticipantName() {
		return participantName;
	}

	public String getTimestamp() {
		return timestamp;
	}

	public Type getType() {
		return type;
	}

	public String getTargetType() {
		return targetType;
	}

	public String getTargetId() {
		return targetId;
	}

	public String getSummary() {
		return summary;
	}

	public String getOldValue() {
		return oldValue;
	}

	public String getNewValue() {
		return newValue;
	}
}
