package info.openrocket.core.live;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.google.gson.Gson;

/**
 * Length-prefixed JSON messages protected with per-connection AES-GCM keys derived
 * from the random secret embedded in a Live invite.
 */
public final class LiveSecureConnection implements Closeable {
	private static final int MAGIC = 0x4f524c56;
	private static final int VERSION = 1;
	private static final int NONCE_LENGTH = 32;
	private static final int MAX_MESSAGE_SIZE = 128 * 1024 * 1024;
	private static final Gson GSON = new Gson();

	private final Socket socket;
	private final DataInputStream input;
	private final DataOutputStream output;
	private final SecretKeySpec sendKey;
	private final SecretKeySpec receiveKey;
	private long sendCounter;
	private long receiveCounter;

	private LiveSecureConnection(Socket socket, SecretKeySpec sendKey, SecretKeySpec receiveKey)
			throws IOException {
		this.socket = socket;
		this.input = new DataInputStream(socket.getInputStream());
		this.output = new DataOutputStream(socket.getOutputStream());
		this.sendKey = sendKey;
		this.receiveKey = receiveKey;
	}

	public static LiveSecureConnection connect(Socket socket, byte[] inviteSecret) throws IOException {
		DataInputStream input = new DataInputStream(socket.getInputStream());
		DataOutputStream output = new DataOutputStream(socket.getOutputStream());
		byte[] clientNonce = randomBytes(NONCE_LENGTH);
		try {
			output.writeInt(MAGIC);
			output.writeInt(VERSION);
			output.write(clientNonce);
			output.write(hmac(inviteSecret, bytes("client"), clientNonce));
			output.flush();

			byte[] serverNonce = input.readNBytes(NONCE_LENGTH);
			byte[] proof = input.readNBytes(32);
			if (serverNonce.length != NONCE_LENGTH || proof.length != 32 || !MessageDigest.isEqual(proof,
					hmac(inviteSecret, bytes("server"), clientNonce, serverNonce))) {
				throw new IOException("OpenRocket Live host authentication failed");
			}
			SecretKeySpec clientToServer = deriveKey(inviteSecret, clientNonce, serverNonce, "client-to-server");
			SecretKeySpec serverToClient = deriveKey(inviteSecret, clientNonce, serverNonce, "server-to-client");
			return new LiveSecureConnection(socket, clientToServer, serverToClient);
		} catch (GeneralSecurityException e) {
			throw new IOException("Unable to secure OpenRocket Live connection", e);
		}
	}

	public static LiveSecureConnection accept(Socket socket, byte[] inviteSecret) throws IOException {
		DataInputStream input = new DataInputStream(socket.getInputStream());
		DataOutputStream output = new DataOutputStream(socket.getOutputStream());
		try {
			if (input.readInt() != MAGIC || input.readInt() != VERSION) {
				throw new IOException("Unsupported OpenRocket Live client");
			}
			byte[] clientNonce = input.readNBytes(NONCE_LENGTH);
			byte[] proof = input.readNBytes(32);
			if (clientNonce.length != NONCE_LENGTH || proof.length != 32 || !MessageDigest.isEqual(proof,
					hmac(inviteSecret, bytes("client"), clientNonce))) {
				throw new IOException("OpenRocket Live invite authentication failed");
			}
			byte[] serverNonce = randomBytes(NONCE_LENGTH);
			output.write(serverNonce);
			output.write(hmac(inviteSecret, bytes("server"), clientNonce, serverNonce));
			output.flush();

			SecretKeySpec clientToServer = deriveKey(inviteSecret, clientNonce, serverNonce, "client-to-server");
			SecretKeySpec serverToClient = deriveKey(inviteSecret, clientNonce, serverNonce, "server-to-client");
			return new LiveSecureConnection(socket, serverToClient, clientToServer);
		} catch (GeneralSecurityException e) {
			throw new IOException("Unable to secure OpenRocket Live connection", e);
		}
	}

	public synchronized void send(LiveWireMessage message) throws IOException {
		byte[] plain = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
		if (plain.length > MAX_MESSAGE_SIZE) {
			throw new IOException("OpenRocket Live message is too large");
		}
		long counter = sendCounter++;
		byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, sendKey, counter, plain);
		output.writeInt(encrypted.length);
		output.writeLong(counter);
		output.write(encrypted);
		output.flush();
	}

	public LiveWireMessage receive() throws IOException {
		int length;
		try {
			length = input.readInt();
		} catch (EOFException e) {
			return null;
		}
		if (length < 16 || length > MAX_MESSAGE_SIZE + 16) {
			throw new IOException("Invalid OpenRocket Live message length");
		}
		long counter = input.readLong();
		if (counter != receiveCounter++) {
			throw new IOException("OpenRocket Live message sequence mismatch");
		}
		byte[] encrypted = input.readNBytes(length);
		if (encrypted.length != length) {
			throw new EOFException("Incomplete OpenRocket Live message");
		}
		byte[] plain = crypt(Cipher.DECRYPT_MODE, receiveKey, counter, encrypted);
		try {
			return GSON.fromJson(new String(plain, StandardCharsets.UTF_8), LiveWireMessage.class);
		} catch (RuntimeException e) {
			throw new IOException("Invalid OpenRocket Live message", e);
		}
	}

	@Override
	public void close() throws IOException {
		socket.close();
	}

	private static byte[] crypt(int mode, SecretKeySpec key, long counter, byte[] value) throws IOException {
		try {
			byte[] nonce = ByteBuffer.allocate(12).putInt(0).putLong(counter).array();
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(mode, key, new GCMParameterSpec(128, nonce));
			return cipher.doFinal(value);
		} catch (GeneralSecurityException e) {
			throw new IOException("OpenRocket Live message authentication failed", e);
		}
	}

	private static SecretKeySpec deriveKey(byte[] secret, byte[] clientNonce, byte[] serverNonce, String info)
			throws GeneralSecurityException {
		byte[] salt = ByteBuffer.allocate(clientNonce.length + serverNonce.length)
				.put(clientNonce).put(serverNonce).array();
		byte[] pseudoRandomKey = hmac(salt, secret);
		byte[] expanded = hmac(pseudoRandomKey, bytes(info), new byte[] { 1 });
		return new SecretKeySpec(expanded, "AES");
	}

	private static byte[] hmac(byte[] key, byte[]... values) throws GeneralSecurityException {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		for (byte[] value : values) {
			mac.update(value);
		}
		return mac.doFinal();
	}

	private static byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		new SecureRandom().nextBytes(bytes);
		return bytes;
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}
}
