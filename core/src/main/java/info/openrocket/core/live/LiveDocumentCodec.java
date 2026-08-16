package info.openrocket.core.live;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.net.URL;
import java.net.URLStreamHandler;
import java.util.UUID;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.file.GeneralRocketLoader;
import info.openrocket.core.file.GeneralRocketSaver;
import info.openrocket.core.file.RocketLoadException;
import info.openrocket.core.util.DecalNotFoundException;

/** Serializes the canonical OpenRocket document used by a Live session. */
public final class LiveDocumentCodec {
	private LiveDocumentCodec() {
	}

	public static byte[] encode(OpenRocketDocument document) throws IOException, DecalNotFoundException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		new GeneralRocketSaver().save(output, document);
		return output.toByteArray();
	}

	public static OpenRocketDocument decode(byte[] data) throws RocketLoadException {
		URL memoryUrl;
		try {
			memoryUrl = new URL(null, "memory://openrocket-live/" + UUID.randomUUID(), new URLStreamHandler() {
				@Override
				protected URLConnection openConnection(URL url) {
					return new URLConnection(url) {
						@Override
						public void connect() {
						}

						@Override
						public java.io.InputStream getInputStream() {
							return new ByteArrayInputStream(data);
						}
					};
				}
			});
		} catch (java.net.MalformedURLException e) {
			throw new IllegalStateException("Could not create an in-memory Live document URL", e);
		}
		GeneralRocketLoader loader = new GeneralRocketLoader(memoryUrl);
		return loader.load(new ByteArrayInputStream(data), "OpenRocket Live");
	}
}
