package cz.bliksoft.hmieink.sync;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.FileEntry;
import cz.bliksoft.hmieink.protocol.HmiDevice;
import cz.bliksoft.hmieink.protocol.Status;
import cz.bliksoft.hmieink.protocol.Volume;

/**
 * Production {@link RemoteFileStore}: binds a live {@link HmiDevice} to one
 * VOLUME + base device-side path (doc/PROTOCOL.md §14).
 * {@link RemoteFileStore}'s relative paths are joined onto {@code basePath} to
 * form the absolute device path each wire command needs.
 */
public final class HmiDeviceRemoteFileStore implements RemoteFileStore {

	// Real SD hardware genuinely takes longer than
	// CommandClient.DEFAULT_TIMEOUT_MILLIS (5s) for a
	// large directory listing or file transfer - every VOLUME=SD operation walks
	// the FAT directory
	// over SPI (doc/PROTOCOL.md §14) - measured directly against a ~220-entry
	// generated font glyph
	// set (design note 96): a single FILE_LIST_REQUEST for that many entries
	// reliably exceeded the
	// default timeout (even after its one built-in retry), though the top-level,
	// few-entry listing
	// and individual small file transfers stayed well within it. A generous flat
	// ceiling here, not a
	// per-entry-count estimate, since FolderSync has no cheap way to know a remote
	// directory's size
	// before listing it.
	private static final long TIMEOUT_MILLIS = 30_000;

	private final HmiDevice device;
	private final int volume;
	private final String basePath;

	public HmiDeviceRemoteFileStore(HmiDevice device, int volume, String basePath) {
		this.device = device;
		this.volume = volume;
		this.basePath = normalize(basePath);
	}

	@Override
	public boolean supportsDirectories() {
		return volume != Volume.PSRAM;
	}

	// A not-yet-existing directory (FILE_NOT_FOUND, doc/PROTOCOL.md §14.1) is
	// treated as empty rather
	// than propagated - the natural reading for a first-ever sync into a device
	// folder that doesn't
	// exist yet, since FILE_UPLOAD (§14.3) auto-creates it (and any deeper missing
	// parents) on the
	// very first upload into it anyway.
	@Override
	public List<FileEntry> list(String relativePath) throws IOException {
		try {
			return device.listFiles(volume, absolute(relativePath), TIMEOUT_MILLIS);
		} catch (CommandNackException e) {
			if (e.getStatus() == Status.FILE_NOT_FOUND) {
				return Collections.emptyList();
			}
			throw e;
		}
	}

	@Override
	public byte[] download(String relativePath) throws IOException {
		return device.downloadFile(volume, absolute(relativePath), TIMEOUT_MILLIS);
	}

	@Override
	public void upload(String relativePath, byte[] data) throws IOException {
		device.uploadFile(volume, absolute(relativePath), data, TIMEOUT_MILLIS);
	}

	@Override
	public void delete(String relativePath) throws IOException {
		device.deleteFile(volume, absolute(relativePath), TIMEOUT_MILLIS);
	}

	private String absolute(String relativePath) {
		if (relativePath.isEmpty()) {
			return basePath;
		}
		return basePath.equals("/") ? "/" + relativePath : basePath + "/" + relativePath;
	}

	private static String normalize(String path) {
		if (path == null || path.isEmpty()) {
			return "/";
		}
		String p = path.startsWith("/") ? path : "/" + path;
		return p.endsWith("/") && p.length() > 1 ? p.substring(0, p.length() - 1) : p;
	}
}
