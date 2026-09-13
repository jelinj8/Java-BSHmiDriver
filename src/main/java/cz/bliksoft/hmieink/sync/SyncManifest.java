package cz.bliksoft.hmieink.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records, per relative path, the SHA-256 hash a file held as of the last
 * successful {@link FolderSync#sync} run - the only way {@link SyncMode#MERGE}
 * can tell "unchanged since last sync" apart from "changed on this side"
 * without any device-side timestamp support (doc/PROTOCOL.md §14.1's
 * FILE_LIST_RESPONSE carries only NAME/ENTRY_TYPE/SIZE). Stored as a plain
 * {@code sha256sum}-style text file - one
 * {@code <64 hex chars>  <relative/path>} line per entry, sorted by path for a
 * stable, diffable, mergeable-by-a-human file - deliberately not a binary or
 * JSON format, since this project has no JSON dependency and the format is
 * simple enough not to need one.
 */
final class SyncManifest {

	private final Map<String, String> hashesByPath;

	private SyncManifest(Map<String, String> hashesByPath) {
		this.hashesByPath = hashesByPath;
	}

	static SyncManifest loadOrEmpty(Path manifestFile) throws IOException {
		if (!Files.exists(manifestFile)) {
			return new SyncManifest(new LinkedHashMap<>());
		}
		Map<String, String> hashesByPath = new LinkedHashMap<>();
		for (String line : Files.readAllLines(manifestFile, StandardCharsets.UTF_8)) {
			if (line.isEmpty()) {
				continue;
			}
			int separator = line.indexOf("  ");
			if (separator < 0) {
				continue; // tolerate a hand-edited/corrupt line rather than failing the whole sync
			}
			hashesByPath.put(line.substring(separator + 2), line.substring(0, separator));
		}
		return new SyncManifest(hashesByPath);
	}

	void save(Path manifestFile) throws IOException {
		List<String> paths = new ArrayList<>(hashesByPath.keySet());
		paths.sort(null);
		StringBuilder sb = new StringBuilder();
		for (String path : paths) {
			sb.append(hashesByPath.get(path)).append("  ").append(path).append('\n');
		}
		Path parent = manifestFile.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.write(manifestFile, sb.toString().getBytes(StandardCharsets.UTF_8));
	}

	String hashAsOfLastSync(String relativePath) {
		return hashesByPath.get(relativePath);
	}

	void record(String relativePath, String hash) {
		hashesByPath.put(relativePath, hash);
	}

	void forget(String relativePath) {
		hashesByPath.remove(relativePath);
	}

	static String sha256Hex(byte[] data) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is a JDK-mandatory algorithm (every conforming JVM provides it) -
			// this can't
			// actually happen, but MessageDigest.getInstance's checked exception forces a
			// handler.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
