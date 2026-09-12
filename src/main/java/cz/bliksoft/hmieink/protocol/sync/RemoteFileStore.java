package cz.bliksoft.hmieink.protocol.sync;

import java.io.IOException;
import java.util.List;

import cz.bliksoft.hmieink.protocol.FileEntry;

/**
 * The remote (device-side) half of {@link FolderSync} - deliberately
 * independent of {@link cz.bliksoft.hmieink.protocol.HmiDevice}/the wire
 * protocol, so {@link FolderSync}'s algorithm can be unit-tested against a
 * plain in-memory fake instead of a real transport/device.
 * {@link HmiDeviceRemoteFileStore} is the production implementation, bound to
 * one device + volume.
 *
 * <p>
 * All paths are relative, forward-slash-separated, with no leading slash (e.g.
 * {@code "sub/a.gly"} for a top-level call {@code list("")}), independent of
 * whatever absolute on-device path prefix the implementation targets.
 */
public interface RemoteFileStore {

	/**
	 * Whether this store can hold real subdirectories. {@code false} for
	 * {@code VOLUME=PSRAM} (doc/PROTOCOL.md §14 - flat, no subdirectories) -
	 * {@link FolderSync} never recurses into local subdirectories against such a
	 * store, reporting them as skipped instead of mishandling them.
	 */
	boolean supportsDirectories();

	/**
	 * Lists one directory level (non-recursive) - {@code relativePath=""} lists the
	 * sync root.
	 */
	List<FileEntry> list(String relativePath) throws IOException;

	byte[] download(String relativePath) throws IOException;

	/**
	 * Overwrites any existing file; creates missing parent directories as needed.
	 */
	void upload(String relativePath, byte[] data) throws IOException;

	void delete(String relativePath) throws IOException;
}
