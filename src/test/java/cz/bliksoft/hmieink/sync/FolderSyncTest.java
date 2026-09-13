package cz.bliksoft.hmieink.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderSyncTest {

	@TempDir
	Path tempDir;

	private Path localDir;
	private Path manifestFile;

	private Path local() throws IOException {
		if (localDir == null) {
			localDir = Files.createDirectory(tempDir.resolve("local"));
			manifestFile = tempDir.resolve("manifest.txt"); // deliberately outside localDir
		}
		return localDir;
	}

	private void writeLocal(String relativePath, String content) throws IOException {
		Path target = local().resolve(relativePath);
		Files.createDirectories(target.getParent());
		Files.write(target, content.getBytes(StandardCharsets.UTF_8));
	}

	private String readLocal(String relativePath) throws IOException {
		return new String(Files.readAllBytes(local().resolve(relativePath)), StandardCharsets.UTF_8);
	}

	@Test
	void pcMasterMirrorsAndDeletesExtras() throws IOException {
		writeLocal("a.txt", "A");
		writeLocal("sub/b.txt", "B");
		FakeRemoteFileStore remote = new FakeRemoteFileStore();
		remote.put("a.txt", "OLD_A");
		remote.put("stale.txt", "STALE");

		SyncResult result = FolderSync.sync(local(), remote, SyncMode.PC_MASTER, manifestFile);

		assertEquals("A", remote.getAsString("a.txt"));
		assertEquals("B", remote.getAsString("sub/b.txt"));
		assertFalse(remote.has("stale.txt"));
		assertTrue(result.uploaded.contains("a.txt"));
		assertTrue(result.uploaded.contains("sub/b.txt"));
		assertTrue(result.deletedRemote.contains("stale.txt"));
	}

	@Test
	void deviceMasterMirrorsAndDeletesExtras() throws IOException {
		writeLocal("stale.txt", "STALE");
		writeLocal("a.txt", "OLD_A");
		FakeRemoteFileStore remote = new FakeRemoteFileStore();
		remote.put("a.txt", "A");
		remote.put("sub/b.txt", "B");

		SyncResult result = FolderSync.sync(local(), remote, SyncMode.DEVICE_MASTER, manifestFile);

		assertEquals("A", readLocal("a.txt"));
		assertEquals("B", readLocal("sub/b.txt"));
		assertFalse(Files.exists(local().resolve("stale.txt")));
		assertTrue(result.downloaded.contains("a.txt"));
		assertTrue(result.downloaded.contains("sub/b.txt"));
		assertTrue(result.deletedLocal.contains("stale.txt"));
	}

	@Test
	void dryRunPcMasterChangesNothing() throws IOException {
		writeLocal("a.txt", "A");
		FakeRemoteFileStore remote = new FakeRemoteFileStore();
		remote.put("a.txt", "OLD_A");
		remote.put("stale.txt", "STALE");

		SyncResult result = FolderSync.sync(local(), remote, SyncMode.PC_MASTER, manifestFile, true);

		assertEquals("OLD_A", remote.getAsString("a.txt")); // untouched
		assertTrue(remote.has("stale.txt")); // not deleted
		assertFalse(Files.exists(manifestFile)); // not written
		assertTrue(result.uploaded.contains("a.txt")); // but still reported as what WOULD happen
		assertTrue(result.deletedRemote.contains("stale.txt"));
	}

	@Test
	void mergeNewLocalFileIsUploaded() throws IOException {
		writeLocal("new.txt", "NEW");
		FakeRemoteFileStore remote = new FakeRemoteFileStore();

		SyncResult result = FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile);

		assertEquals("NEW", remote.getAsString("new.txt"));
		assertTrue(result.uploaded.contains("new.txt"));
	}

	@Test
	void mergeNewRemoteFileIsDownloaded() throws IOException {
		local();
		FakeRemoteFileStore remote = new FakeRemoteFileStore();
		remote.put("new.txt", "NEW");

		SyncResult result = FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile);

		assertEquals("NEW", readLocal("new.txt"));
		assertTrue(result.downloaded.contains("new.txt"));
	}

	@Test
	void mergeIdenticalContentIsUnchanged() throws IOException {
		writeLocal("same.txt", "SAME");
		FakeRemoteFileStore remote = new FakeRemoteFileStore();
		remote.put("same.txt", "SAME");

		SyncResult result = FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile);

		assertTrue(result.unchanged.contains("same.txt"));
		assertTrue(result.uploaded.isEmpty());
		assertTrue(result.downloaded.isEmpty());
	}

	@Test
	void mergePropagatesLocalChangeAfterPriorSync() throws IOException {
		writeLocal("f.txt", "v1");
		FakeRemoteFileStore remote = new FakeRemoteFileStore();
		FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile); // establishes baseline

		writeLocal("f.txt", "v2"); // local changes, remote doesn't
		SyncResult result = FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile);

		assertEquals("v2", remote.getAsString("f.txt"));
		assertTrue(result.uploaded.contains("f.txt"));
	}

	@Test
	void mergePropagatesRemoteChangeAfterPriorSync() throws IOException {
		writeLocal("f.txt", "v1");
		FakeRemoteFileStore remote = new FakeRemoteFileStore();
		FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile); // establishes baseline

		remote.put("f.txt", "v2"); // remote changes, local doesn't
		SyncResult result = FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile);

		assertEquals("v2", readLocal("f.txt"));
		assertTrue(result.downloaded.contains("f.txt"));
	}

	@Test
	void mergeDetectsConflictWhenBothChangedDifferently() throws IOException {
		writeLocal("f.txt", "v1");
		FakeRemoteFileStore remote = new FakeRemoteFileStore();
		FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile); // establishes baseline

		writeLocal("f.txt", "vLocal");
		remote.put("f.txt", "vRemote");
		SyncResult result = FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile);

		assertTrue(result.conflicted.contains("f.txt"));
		assertEquals("vLocal", readLocal("f.txt")); // left untouched on both sides
		assertEquals("vRemote", remote.getAsString("f.txt"));
	}

	@Test
	void mergePropagatesLocalDeletionWhenRemoteUnchanged() throws IOException {
		writeLocal("f.txt", "v1");
		FakeRemoteFileStore remote = new FakeRemoteFileStore();
		FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile); // establishes baseline

		Files.delete(local().resolve("f.txt"));
		SyncResult result = FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile);

		assertFalse(remote.has("f.txt"));
		assertTrue(result.deletedRemote.contains("f.txt"));
	}

	@Test
	void mergePropagatesRemoteDeletionWhenLocalUnchanged() throws IOException {
		writeLocal("f.txt", "v1");
		FakeRemoteFileStore remote = new FakeRemoteFileStore();
		FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile); // establishes baseline

		remote.delete("f.txt");
		SyncResult result = FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile);

		assertFalse(Files.exists(local().resolve("f.txt")));
		assertTrue(result.deletedLocal.contains("f.txt"));
	}

	@Test
	void mergeConflictWhenLocalChangedButRemoteDeleted() throws IOException {
		writeLocal("f.txt", "v1");
		FakeRemoteFileStore remote = new FakeRemoteFileStore();
		FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile); // establishes baseline

		writeLocal("f.txt", "v2");
		remote.delete("f.txt");
		SyncResult result = FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile);

		assertTrue(result.conflicted.contains("f.txt"));
		assertEquals("v2", readLocal("f.txt")); // untouched
		assertFalse(remote.has("f.txt")); // untouched (still absent)
	}

	@Test
	void mergeConflictWhenRemoteChangedButLocalDeleted() throws IOException {
		writeLocal("f.txt", "v1");
		FakeRemoteFileStore remote = new FakeRemoteFileStore();
		FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile); // establishes baseline

		Files.delete(local().resolve("f.txt"));
		remote.put("f.txt", "v2");
		SyncResult result = FolderSync.sync(local(), remote, SyncMode.MERGE, manifestFile);

		assertTrue(result.conflicted.contains("f.txt"));
		assertFalse(Files.exists(local().resolve("f.txt"))); // untouched (still absent)
		assertEquals("v2", remote.getAsString("f.txt")); // untouched
	}

	@Test
	void skipsLocalSubdirectoriesWhenRemoteDoesNotSupportThem() throws IOException {
		writeLocal("top.txt", "TOP");
		writeLocal("sub/nested.txt", "NESTED");
		FakeRemoteFileStore remote = new FakeRemoteFileStore();
		remote.setSupportsDirectories(false);

		SyncResult result = FolderSync.sync(local(), remote, SyncMode.PC_MASTER, manifestFile);

		assertEquals("TOP", remote.getAsString("top.txt"));
		assertFalse(remote.has("sub/nested.txt"));
		assertTrue(result.skippedLocalDirectories.contains("sub"));
	}
}
