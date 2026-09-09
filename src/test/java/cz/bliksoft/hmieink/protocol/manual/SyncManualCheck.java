package cz.bliksoft.hmieink.protocol.manual;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.SerialHmiDevice;
import cz.bliksoft.hmieink.protocol.Volume;
import cz.bliksoft.hmieink.protocol.sync.FolderSync;
import cz.bliksoft.hmieink.protocol.sync.SyncMode;
import cz.bliksoft.hmieink.protocol.sync.SyncResult;

/**
 * Manual, real-hardware verification of {@link FolderSync} end-to-end - the algorithm's full
 * PC_MASTER/DEVICE_MASTER/MERGE decision tree is already exhaustively covered by {@code
 * FolderSyncTest} against an in-memory fake, so this focuses on what only real hardware can
 * confirm: the actual FILE_LIST/DOWNLOAD/UPLOAD/DELETE round-trip, and specifically that uploading
 * into a not-yet-existing nested subdirectory now works (firmware design note 94's
 * {@code StorageManager::upload()} auto-mkdir fix - this test's local tree deliberately includes a
 * {@code sub/} subdirectory to exercise exactly that). Uses a throwaway device-side folder
 * ({@code /sync_test_<random>}) so it never collides with real content. NOT part of the automated
 * {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.SyncManualCheck COM5
 * </pre>
 */
public final class SyncManualCheck {

	private static int failures = 0;

	private SyncManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: SyncManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];
		String devicePath = "/sync_test_" + Long.toHexString(System.nanoTime());

		try (SerialHmiDevice device = new SerialHmiDevice(portDescriptor)) {
			System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
					+ " baud (this resets the board and re-runs its boot self-test)...");
			device.connect();
			device.handshake();

			boolean sdPresent = ((Long) device.storageInfo(Volume.SD).get("PRESENT")) != 0;
			int volume = sdPresent ? Volume.SD : Volume.INTERNAL;
			System.out.println("-> using VOLUME=" + (sdPresent ? "SD" : "INTERNAL") + ", device path " + devicePath);

			Path localDir = Files.createTempDirectory("bshmisync-check");
			Path manifestFile = localDir.resolveSibling(localDir.getFileName() + ".manifest");
			try {
				System.out.println();
				System.out.println("-- PC_MASTER: initial push, including a nested subdirectory --");
				writeLocal(localDir, "a.txt", "A");
				writeLocal(localDir, "sub/b.txt", "B"); // exercises the auto-mkdir fix specifically
				SyncResult r1 = FolderSync.sync(localDir, device, volume, devicePath, SyncMode.PC_MASTER, manifestFile);
				System.out.print(r1);
				check("a.txt uploaded", r1.uploaded.contains("a.txt"));
				check("sub/b.txt uploaded (nested subdirectory auto-created on device)",
						r1.uploaded.contains("sub/b.txt"));
				check("no conflicts", !r1.hasConflicts());

				System.out.println();
				System.out.println("-- PC_MASTER again: local now missing a.txt, changed sub/b.txt - mirror should "
						+ "delete a.txt remotely and re-upload sub/b.txt --");
				Files.delete(localDir.resolve("a.txt"));
				writeLocal(localDir, "sub/b.txt", "B2");
				SyncResult r2 = FolderSync.sync(localDir, device, volume, devicePath, SyncMode.PC_MASTER, manifestFile);
				System.out.print(r2);
				check("a.txt deleted remotely (mirror deletes extras)", r2.deletedRemote.contains("a.txt"));
				check("sub/b.txt re-uploaded", r2.uploaded.contains("sub/b.txt"));

				System.out.println();
				System.out.println("-- DEVICE_MASTER: local tree should now become an exact mirror of the device "
						+ "(only sub/b.txt=B2) --");
				writeLocal(localDir, "stale_local_only.txt", "STALE"); // should get deleted by the mirror
				SyncResult r3 = FolderSync.sync(localDir, device, volume, devicePath, SyncMode.DEVICE_MASTER,
						manifestFile);
				System.out.print(r3);
				check("stale_local_only.txt deleted locally (mirror deletes extras)",
						r3.deletedLocal.contains("stale_local_only.txt"));
				check("sub/b.txt content matches device after DEVICE_MASTER",
						"B2".equals(readLocal(localDir, "sub/b.txt")));
				check("a.txt correctly absent locally (device doesn't have it)",
						!Files.exists(localDir.resolve("a.txt")));

				System.out.println();
				System.out.println("-- MERGE: local edits sub/b.txt, sync should push it (remote side unchanged "
						+ "since the manifest baseline just established above) --");
				writeLocal(localDir, "sub/b.txt", "B3");
				SyncResult r4 = FolderSync.sync(localDir, device, volume, devicePath, SyncMode.MERGE, manifestFile);
				System.out.print(r4);
				check("sub/b.txt pushed via MERGE", r4.uploaded.contains("sub/b.txt"));
				check("no conflicts", !r4.hasConflicts());
			} finally {
				System.out.println();
				System.out.println("-- cleanup: deleting throwaway device folder contents --");
				cleanupRecursive(device, volume, devicePath);
			}

			System.out.println();
			if (failures == 0) {
				System.out.println("ALL CHECKS PASSED");
			} else {
				System.out.println("FAILURES: " + failures);
				System.exit(1);
			}
		}
	}

	private static void cleanupRecursive(SerialHmiDevice device, int volume, String path) {
		try {
			for (cz.bliksoft.hmieink.protocol.FileEntry entry : device.listFiles(volume, path)) {
				String childPath = path + "/" + entry.getName();
				if (entry.isDirectory()) {
					cleanupRecursive(device, volume, childPath);
				} else {
					device.deleteFile(volume, childPath);
				}
			}
		} catch (IOException e) {
			System.err.println("   (cleanup warning, non-fatal: " + e.getMessage() + ")");
		}
	}

	private static void writeLocal(Path localDir, String relativePath, String content) throws IOException {
		Path target = localDir.resolve(relativePath);
		Files.createDirectories(target.getParent());
		Files.write(target, content.getBytes(StandardCharsets.UTF_8));
	}

	private static String readLocal(Path localDir, String relativePath) throws IOException {
		return new String(Files.readAllBytes(localDir.resolve(relativePath)), StandardCharsets.UTF_8);
	}

	private static void check(String description, boolean condition) {
		if (condition) {
			System.out.println("   OK: " + description);
		} else {
			System.out.println("   FAIL: " + description);
			failures++;
		}
	}
}
