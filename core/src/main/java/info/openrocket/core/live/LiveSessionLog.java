package info.openrocket.core.live;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/**
 * Append-only JSON Lines storage for a participant's local Live session history.
 */
public final class LiveSessionLog implements Closeable {
	private static final Gson GSON = new Gson();

	private final Path path;
	private final BufferedWriter writer;

	public LiveSessionLog(Path path) throws IOException {
		this.path = path.toAbsolutePath();
		Path parent = this.path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		this.writer = Files.newBufferedWriter(this.path, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	public static Path pathForDesign(File designFile) {
		String name = designFile.getName();
		String lower = name.toLowerCase();
		if (lower.endsWith(".ork.gz")) {
			name = name.substring(0, name.length() - ".ork.gz".length());
		} else if (lower.endsWith(".ork")) {
			name = name.substring(0, name.length() - ".ork".length());
		}
		return designFile.toPath().toAbsolutePath().resolveSibling(name + ".orklog");
	}

	public synchronized void append(LiveSessionEvent event) throws IOException {
		writer.write(GSON.toJson(event));
		writer.newLine();
		writer.flush();
	}

	public Path getPath() {
		return path;
	}

	@Override
	public synchronized void close() throws IOException {
		writer.close();
	}

	public static List<LiveSessionEvent> read(Path path) throws IOException {
		if (!Files.exists(path)) {
			return Collections.emptyList();
		}

		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		List<LiveSessionEvent> events = new ArrayList<>(lines.size());
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.isBlank()) {
				continue;
			}
			try {
				events.add(GSON.fromJson(line, LiveSessionEvent.class));
			} catch (JsonParseException e) {
				if (i != lines.size() - 1) {
					throw new IOException("Invalid OpenRocket Live log entry at line " + (i + 1), e);
				}
			}
		}
		return events;
	}
}
