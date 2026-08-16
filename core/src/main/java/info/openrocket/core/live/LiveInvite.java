package info.openrocket.core.live;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Connection details copied from a Live host to a participant. */
public final class LiveInvite {
	public static final String SCHEME = "openrocket-live";

	private final String host;
	private final int port;
	private final String sessionId;
	private final byte[] secret;

	public LiveInvite(String host, int port, String sessionId, byte[] secret) {
		this.host = Objects.requireNonNull(host);
		this.port = port;
		this.sessionId = Objects.requireNonNull(sessionId);
		this.secret = secret.clone();
	}

	public static LiveInvite create(String host, int port) {
		return create(host, port, UUID.randomUUID().toString());
	}

	public static LiveInvite create(String host, int port, String sessionId) {
		byte[] secret = new byte[32];
		new SecureRandom().nextBytes(secret);
		return new LiveInvite(host, port, sessionId, secret);
	}

	public static LiveInvite parse(String value) {
		URI uri = URI.create(value.trim());
		if (!SCHEME.equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1) {
			throw new IllegalArgumentException("Invalid OpenRocket Live invite");
		}
		String path = uri.getPath();
		if (path == null || path.length() < 2 || uri.getFragment() == null) {
			throw new IllegalArgumentException("Incomplete OpenRocket Live invite");
		}
		byte[] secret;
		try {
			secret = Base64.getUrlDecoder().decode(uri.getFragment());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid OpenRocket Live invite secret", e);
		}
		if (secret.length != 32) {
			throw new IllegalArgumentException("Invalid OpenRocket Live invite secret");
		}
		return new LiveInvite(uri.getHost(), uri.getPort(), path.substring(1), secret);
	}

	public String encode() {
		try {
			return new URI(SCHEME, null, host, port, "/" + sessionId, null,
					Base64.getUrlEncoder().withoutPadding().encodeToString(secret)).toString();
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid Live host address", e);
		}
	}

	public String getHost() {
		return host;
	}

	public int getPort() {
		return port;
	}

	public String getSessionId() {
		return sessionId;
	}

	public byte[] getSecret() {
		return secret.clone();
	}
}
