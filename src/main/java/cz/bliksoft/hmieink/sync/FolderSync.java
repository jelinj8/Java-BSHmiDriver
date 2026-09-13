package cz.bliksoft.hmieink.sync;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cz.bliksoft.hmieink.protocol.FileEntry;
import cz.bliksoft.hmieink.protocol.HmiDevice;

/**
 * Recursively syncs a local folder against a {@link RemoteFileStore} (device
 * storage) in one of three {@link SyncMode}s. Change detection has no
 * device-side timestamps to work with (doc/PROTOCOL.md §14.1's
 * FILE_LIST_RESPONSE carries only NAME/ENTRY_TYPE/SIZE) -
 * {@link SyncMode#MERGE} instead compares SHA-256 content hashes against a
 * small local {@link SyncManifest} recording each path's hash as of the last
 * successful sync, letting it tell "unchanged since last sync" apart from
 * "changed on this side" independently for each side.
 * {@link SyncMode#PC_MASTER}/{@link SyncMode#DEVICE_MASTER} need no such
 * history - they simply make the target side an exact mirror of the source
 * side, deleting whatever the target has that the source doesn't.
 *
 * <p>
 * A local subdirectory is skipped (reported in
 * {@link SyncResult#skippedLocalDirectories}, not silently mishandled) whenever
 * {@link RemoteFileStore#supportsDirectories()} is {@code false} (VOLUME=PSRAM,
 * which is flat) - only top-level files sync against such a store.
 */
public final class FolderSync {

	private FolderSync() {
	}

	/**
	 * Convenience overload binding directly to a live device + volume + base device
	 * path.
	 */
	public static SyncResult sync(Path localDir, HmiDevice device, int volume, String devicePath, SyncMode mode,
			Path manifestFile) throws IOException {
		return sync(localDir, new HmiDeviceRemoteFileStore(device, volume, devicePath), mode, manifestFile, false);
	}

	public static SyncResult sync(Path localDir, RemoteFileStore remote, SyncMode mode, Path manifestFile)
			throws IOException {
		return sync(localDir, remote, mode, manifestFile, false);
	}

	/**
	 * @param dryRun when true, computes and returns exactly what a real run would
	 *               do (including what {@link SyncResult#conflicted} would contain)
	 *               without uploading, downloading, deleting, or touching the
	 *               manifest file - useful to preview a mirror mode's deletions
	 *               before committing to them.
	 */
	public static SyncResult sync(Path localDir, RemoteFileStore remote, SyncMode mode, Path manifestFile,
			boolean dryRun) throws IOException {
		SyncResult result = new SyncResult();
		Map<String, Path> localFiles = new LinkedHashMap<>();
		// Excluded so the manifest never syncs itself as if it were a regular asset - a
		// real risk if
		// a caller's manifestFile happens to live inside localDir (self-referential:
		// hashing the
		// manifest changes the manifest, so its own entry would go stale every single
		// run).
		Path excludedManifest = manifestFile.toAbsolutePath().normalize();
		indexLocal(localDir, localDir, remote.supportsDirectories(), excludedManifest, localFiles, result);
		Map<String, FileEntry> remoteFiles = new LinkedHashMap<>();
		indexRemote(remote, "", remoteFiles);
		SyncManifest manifest = SyncManifest.loadOrEmpty(manifestFile);

		switch (mode) {
		case PC_MASTER:
			pcMaster(remote, localFiles, remoteFiles, manifest, result, dryRun);
			break;
		case DEVICE_MASTER:
			deviceMaster(localDir, remote, localFiles, remoteFiles, manifest, result, dryRun);
			break;
		case MERGE:
			merge(localDir, remote, localFiles, remoteFiles, manifest, result, dryRun);
			break;
		default:
			throw new IllegalArgumentException("unhandled mode " + mode);
		}

		if (!dryRun) {
			manifest.save(manifestFile);
		}
		return result;
	}

	private static void indexLocal(Path root, Path dir, boolean supportsDirectories, Path excludedManifest,
			Map<String, Path> out, SyncResult result) throws IOException {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path entry : stream) {
				if (entry.toAbsolutePath().normalize().equals(excludedManifest)) {
					continue;
				}
				String relative = toRelative(root, entry);
				if (Files.isDirectory(entry)) {
					if (!supportsDirectories) {
						result.skippedLocalDirectories.add(relative);
						continue;
					}
					indexLocal(root, entry, supportsDirectories, excludedManifest, out, result);
				} else {
					out.put(relative, entry);
				}
			}
		}
	}

	private static void indexRemote(RemoteFileStore remote, String relativeDir, Map<String, FileEntry> out)
			throws IOException {
		for (FileEntry entry : remote.list(relativeDir)) {
			String relativePath = relativeDir.isEmpty() ? entry.getName() : relativeDir + "/" + entry.getName();
			if (entry.isDirectory()) {
				indexRemote(remote, relativePath, out);
			} else {
				out.put(relativePath, entry);
			}
		}
	}

	private static void pcMaster(RemoteFileStore remote, Map<String, Path> localFiles,
			Map<String, FileEntry> remoteFiles, SyncManifest manifest, SyncResult result, boolean dryRun)
			throws IOException {
		for (Map.Entry<String, Path> e : localFiles.entrySet()) {
			String path = e.getKey();
			byte[] data = Files.readAllBytes(e.getValue());
			if (!dryRun) {
				remote.upload(path, data);
			}
			result.uploaded.add(path);
			manifest.record(path, SyncManifest.sha256Hex(data));
		}
		for (String path : remoteFiles.keySet()) {
			if (!localFiles.containsKey(path)) {
				if (!dryRun) {
					remote.delete(path);
				}
				result.deletedRemote.add(path);
				manifest.forget(path);
			}
		}
	}

	private static void deviceMaster(Path localDir, RemoteFileStore remote, Map<String, Path> localFiles,
			Map<String, FileEntry> remoteFiles, SyncManifest manifest, SyncResult result, boolean dryRun)
			throws IOException {
		for (String path : remoteFiles.keySet()) {
			byte[] data = remote.download(path);
			if (!dryRun) {
				writeLocal(localDir, path, data);
			}
			result.downloaded.add(path);
			manifest.record(path, SyncManifest.sha256Hex(data));
		}
		for (Map.Entry<String, Path> e : localFiles.entrySet()) {
			String path = e.getKey();
			if (!remoteFiles.containsKey(path)) {
				if (!dryRun) {
					Files.deleteIfExists(e.getValue());
				}
				result.deletedLocal.add(path);
				manifest.forget(path);
			}
		}
		if (!dryRun) {
			pruneEmptyLocalDirectories(localDir);
		}
	}

	private static void merge(Path localDir, RemoteFileStore remote, Map<String, Path> localFiles,
			Map<String, FileEntry> remoteFiles, SyncManifest manifest, SyncResult result, boolean dryRun)
			throws IOException {
		Set<String> allPaths = new TreeSet<>();
		allPaths.addAll(localFiles.keySet());
		allPaths.addAll(remoteFiles.keySet());

		for (String path : allPaths) {
			boolean localPresent = localFiles.containsKey(path);
			boolean remotePresent = remoteFiles.containsKey(path);
			String lastHash = manifest.hashAsOfLastSync(path);

			byte[] localData = localPresent ? Files.readAllBytes(localFiles.get(path)) : null;
			byte[] remoteData = remotePresent ? remote.download(path) : null;
			String localHash = localPresent ? SyncManifest.sha256Hex(localData) : null;
			String remoteHash = remotePresent ? SyncManifest.sha256Hex(remoteData) : null;

			if (localPresent && remotePresent) {
				if (localHash.equals(remoteHash)) {
					result.unchanged.add(path);
					manifest.record(path, localHash);
				} else if (lastHash == null) {
					result.conflicted.add(path); // both sides already had it before we ever tracked it
				} else if (localHash.equals(lastHash)) {
					if (!dryRun) {
						writeLocal(localDir, path, remoteData);
					}
					result.downloaded.add(path); // local unchanged, remote changed -> remote wins
					manifest.record(path, remoteHash);
				} else if (remoteHash.equals(lastHash)) {
					if (!dryRun) {
						remote.upload(path, localData);
					}
					result.uploaded.add(path); // remote unchanged, local changed -> local wins
					manifest.record(path, localHash);
				} else {
					result.conflicted.add(path); // both changed, differently
				}
			} else if (localPresent) {
				if (lastHash == null) {
					if (!dryRun) {
						remote.upload(path, localData);
					}
					result.uploaded.add(path); // new local file
					manifest.record(path, localHash);
				} else if (localHash.equals(lastHash)) {
					if (!dryRun) {
						Files.deleteIfExists(localFiles.get(path));
					}
					result.deletedLocal.add(path); // local unchanged, remote deleted it - propagate
					manifest.forget(path);
				} else {
					result.conflicted.add(path); // local changed, but remote deleted the file
				}
			} else {
				if (lastHash == null) {
					if (!dryRun) {
						writeLocal(localDir, path, remoteData);
					}
					result.downloaded.add(path); // new remote file
					manifest.record(path, remoteHash);
				} else if (remoteHash.equals(lastHash)) {
					if (!dryRun) {
						remote.delete(path);
					}
					result.deletedRemote.add(path); // remote unchanged, local deleted it - propagate
					manifest.forget(path);
				} else {
					result.conflicted.add(path); // remote changed, but local deleted the file
				}
			}
		}
	}

	private static void writeLocal(Path localDir, String relativePath, byte[] data) throws IOException {
		Path target = localDir.resolve(relativePath.replace('/', java.io.File.separatorChar));
		Path parent = target.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.write(target, data);
	}

	private static void pruneEmptyLocalDirectories(Path root) throws IOException {
		List<Path> directories;
		try (Stream<Path> walk = Files.walk(root)) {
			directories = walk.filter(Files::isDirectory).filter(p -> !p.equals(root))
					.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).collect(Collectors.toList());
		}
		for (Path dir : directories) {
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
				if (!stream.iterator().hasNext()) {
					Files.delete(dir);
				}
			}
		}
	}

	private static String toRelative(Path root, Path entry) {
		return root.relativize(entry).toString().replace('\\', '/');
	}
}
