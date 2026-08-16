package info.openrocket.core.live;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/** Versioned message exchanged over an authenticated Live connection. */
public final class LiveWireMessage {
	public enum Type {
		HELLO,
		WELCOME,
		PROPOSE_DOCUMENT,
		PROPOSE_OFFLINE_BRANCH,
		DOCUMENT_UPDATE,
		EVENT,
		SETTINGS,
		HOST_TRANSFER_REQUEST,
		HOST_TRANSFER_READY,
		HOST_TRANSFER,
		CHAT,
		PRESENCE,
		CURSOR,
		ERROR,
		LEAVE
	}

	private Type type;
	private String sessionId;
	private long revision;
	private long baseRevision;
	private String participantId;
	private String participantName;
	private LiveSessionEvent event;
	private List<LiveSessionEvent> history;
	private byte[] document;
	private String text;
	private String settings;
	private String timestamp;
	private String surfaceId;
	private double x;
	private double y;

	private LiveWireMessage(Type type) {
		this.type = type;
	}

	public static LiveWireMessage hello(String sessionId, String participantId, String participantName) {
		LiveWireMessage message = new LiveWireMessage(Type.HELLO);
		message.sessionId = sessionId;
		message.participantId = participantId;
		message.participantName = participantName;
		return message;
	}

	public static LiveWireMessage welcome(String sessionId, String roomName, String settings, long revision,
			byte[] document, List<LiveSessionEvent> history) {
		LiveWireMessage message = new LiveWireMessage(Type.WELCOME);
		message.sessionId = sessionId;
		message.text = roomName;
		message.settings = settings;
		message.revision = revision;
		message.document = document;
		message.history = history;
		return message;
	}

	public static LiveWireMessage proposal(String sessionId, long baseRevision, String participantId,
			String participantName, byte[] document) {
		LiveWireMessage message = new LiveWireMessage(Type.PROPOSE_DOCUMENT);
		message.sessionId = sessionId;
		message.baseRevision = baseRevision;
		message.participantId = participantId;
		message.participantName = participantName;
		message.document = document;
		return message;
	}

	public static LiveWireMessage offlineProposal(String sessionId, long baseRevision, String participantId,
			String participantName, byte[] document) {
		LiveWireMessage message = proposal(sessionId, baseRevision, participantId, participantName, document);
		message.type = Type.PROPOSE_OFFLINE_BRANCH;
		return message;
	}

	public static LiveWireMessage update(String sessionId, long revision, LiveSessionEvent event,
			byte[] document) {
		LiveWireMessage message = new LiveWireMessage(Type.DOCUMENT_UPDATE);
		message.sessionId = sessionId;
		message.revision = revision;
		message.event = event;
		message.document = document;
		return message;
	}

	public static LiveWireMessage event(String sessionId, long revision, LiveSessionEvent event) {
		LiveWireMessage message = new LiveWireMessage(Type.EVENT);
		message.sessionId = sessionId;
		message.revision = revision;
		message.event = event;
		return message;
	}

	public static LiveWireMessage settings(String sessionId, String settings) {
		LiveWireMessage message = new LiveWireMessage(Type.SETTINGS);
		message.sessionId = sessionId;
		message.settings = settings;
		return message;
	}

	public static LiveWireMessage hostTransferRequest(String sessionId) {
		return newSessionMessage(Type.HOST_TRANSFER_REQUEST, sessionId);
	}

	public static LiveWireMessage hostTransferReady(String sessionId, String participantId,
			String participantName, String invite) {
		LiveWireMessage message = participantMessage(Type.HOST_TRANSFER_READY, sessionId,
				participantId, participantName);
		message.text = invite;
		return message;
	}

	public static LiveWireMessage hostTransfer(String sessionId, long revision, String newHostId,
			String invite, LiveSessionEvent event) {
		LiveWireMessage message = newSessionMessage(Type.HOST_TRANSFER, sessionId);
		message.revision = revision;
		message.participantId = newHostId;
		message.text = invite;
		message.event = event;
		return message;
	}

	public static LiveWireMessage error(String text) {
		LiveWireMessage message = new LiveWireMessage(Type.ERROR);
		message.text = text;
		return message;
	}

	public static LiveWireMessage chat(String sessionId, String participantId, String participantName, String text) {
		return chat(sessionId, participantId, participantName, text, null);
	}

	public static LiveWireMessage chat(String sessionId, String participantId, String participantName, String text,
			LiveSessionEvent event) {
		LiveWireMessage message = participantMessage(Type.CHAT, sessionId, participantId, participantName);
		message.text = text;
		message.event = event;
		message.timestamp = event == null ? Instant.now().toString() : event.getTimestamp();
		return message;
	}

	public static LiveWireMessage presence(String sessionId, String participantId, String participantName,
			String surfaceId) {
		LiveWireMessage message = participantMessage(Type.PRESENCE, sessionId, participantId, participantName);
		message.surfaceId = surfaceId;
		message.timestamp = Instant.now().toString();
		return message;
	}

	public static LiveWireMessage cursor(String sessionId, String participantId, String participantName,
			String surfaceId, double x, double y) {
		LiveWireMessage message = participantMessage(Type.CURSOR, sessionId, participantId, participantName);
		message.surfaceId = surfaceId;
		message.x = x;
		message.y = y;
		message.timestamp = Instant.now().toString();
		return message;
	}

	private static LiveWireMessage participantMessage(Type type, String sessionId, String participantId,
			String participantName) {
		LiveWireMessage message = new LiveWireMessage(type);
		message.sessionId = sessionId;
		message.participantId = participantId;
		message.participantName = participantName;
		return message;
	}

	private static LiveWireMessage newSessionMessage(Type type, String sessionId) {
		LiveWireMessage message = new LiveWireMessage(type);
		message.sessionId = sessionId;
		return message;
	}

	public Type getType() {
		return type;
	}

	public String getSessionId() {
		return sessionId;
	}

	public long getRevision() {
		return revision;
	}

	public long getBaseRevision() {
		return baseRevision;
	}

	public String getParticipantId() {
		return participantId;
	}

	public String getParticipantName() {
		return participantName;
	}

	public LiveSessionEvent getEvent() {
		return event;
	}

	public List<LiveSessionEvent> getHistory() {
		return history == null ? Collections.emptyList() : Collections.unmodifiableList(history);
	}

	public byte[] getDocument() {
		return document;
	}

	public String getText() {
		return text;
	}

	public String getTimestamp() {
		return timestamp;
	}

	public String getSettings() {
		return settings;
	}

	public String getSurfaceId() {
		return surfaceId;
	}

	public double getX() {
		return x;
	}

	public double getY() {
		return y;
	}
}
