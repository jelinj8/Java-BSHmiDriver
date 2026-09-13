package cz.bliksoft.hmieink.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cz.bliksoft.hmieink.protocol.FileEntry;

/**
 * In-memory {@link RemoteFileStore} fake so {@link FolderSyncTest} needs no
 * real device/transport.
 */
final class FakeRemoteFileStore implements RemoteFileStore {

	private final Map<String, byte[]> files = new LinkedHashMap<>();
	private boolean supportsDirectories = true;

	void put(String path, String content) {
		files.put(path, content.getBytes(StandardCharsets.UTF_8));
	}

	boolean has(String path) {
		return files.containsKey(path);
	}

	String getAsString(String path) {
		byte[] data = files.get(path);
		return data == null ? null : new String(data, StandardCharsets.UTF_8);
	}

	void setSupportsDirectories(boolean value) {
		this.supportsDirectories = value;
	}

	@Override
	public boolean supportsDirectories() {
		return supportsDirectories;
	}

	@Override
	public List<FileEntry> list(String relativePath) {
		String prefix = relativePath.isEmpty() ? "" : relativePath + "/";
		Set<String> seenDirs = new LinkedHashSet<>();
		List<FileEntry> result = new ArrayList<>();
		for (Map.Entry<String, byte[]> e : files.entrySet()) {
			if (!e.getKey().startsWith(prefix)) {
				continue;
			}
			String remainder = e.getKey().substring(prefix.length());
			int slash = remainder.indexOf('/');
			if (slash < 0) {
				result.add(new FileEntry(remainder, false, e.getValue().length));
			} else if (seenDirs.add(remainder.substring(0, slash))) {
				result.add(new FileEntry(remainder.substring(0, slash), true, 0));
			}
		}
		return result;
	}

	@Override
	public byte[] download(String relativePath) throws IOException {
		byte[] data = files.get(relativePath);
		if (data == null) {
			throw new IOException("not found: " + relativePath);
		}
		return data;
	}

	@Override
	public void upload(String relativePath, byte[] data) {
		files.put(relativePath, data);
	}

	@Override
	public void delete(String relativePath) throws IOException {
		if (files.remove(relativePath) == null) {
			throw new IOException("not found: " + relativePath);
		}
	}
}
