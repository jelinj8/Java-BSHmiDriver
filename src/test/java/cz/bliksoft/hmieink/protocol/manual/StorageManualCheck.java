package cz.bliksoft.hmieink.protocol.manual;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.EntryType;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.Volume;

/**
 * Manual, real-hardware verification of the storage manager (doc/PROTOCOL.md
 * §14): for each of VOLUME=INTERNAL, VOLUME=SD, and VOLUME=PSRAM, queries
 * STORAGE_INFO, uploads a small test file, confirms it appears in FILE_LIST
 * with the right size, downloads it back and confirms the bytes match exactly,
 * copies it (same-volume) and confirms the source is untouched and the copy is
 * byte-identical, renames the copy and confirms the old name is gone and the
 * new one holds the content, renames the original file onto that
 * already-existing name to confirm FILE_RENAME's overwrite semantics, then
 * deletes and confirms FILE_LIST no longer shows any of the test files. A
 * separate cross-volume check (INTERNAL -&gt; PSRAM) confirms FILE_COPY's
 * cross-volume form. Like {@code ScreenReadbackManualCheck}, this asserts
 * programmatically rather than relying on a human looking at the panel (there's
 * nothing to look at - this exercises file I/O, not drawing). VOLUME=SD is
 * skipped (with a warning, not a failure) if STORAGE_INFO reports no card
 * present, since that's a legitimate hot-plug state, not a bug. NOT part of the
 * automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.StorageManualCheck COM5
 * </pre>
 */
public final class StorageManualCheck {

	private static final String TEST_PATH = "/crowpanel_test.txt";
	private static final String TEST_COPY_PATH = "/crowpanel_test_copy.txt";
	private static final String TEST_RENAME_PATH = "/crowpanel_test_renamed.txt";
	private static final String CROSS_VOLUME_PATH = "/crowpanel_test_cross.txt";

	private static int failures = 0;

	private StorageManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: StorageManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			testVolume(client, Volume.INTERNAL, "INTERNAL");
			testVolume(client, Volume.SD, "SD");
			testVolume(client, Volume.PSRAM, "PSRAM");
			testCrossVolumeCopy(client);

			if (failures == 0) {
				System.out.println("ALL CHECKS PASSED");
			} else {
				System.out.println(failures + " CHECK(S) FAILED - see above");
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	private static void testVolume(CommandClient client, int volume, String label) throws Exception {
		System.out.println("== VOLUME=" + label + " ==");

		System.out.println("-> STORAGE_INFO_REQUEST");
		long[] presentTotalFree = storageInfo(client, volume);
		boolean present = presentTotalFree[0] != 0;
		long total = presentTotalFree[1];
		long free = presentTotalFree[2];
		System.out.println("   PRESENT=" + present + " TOTAL=" + total + " FREE=" + free);
		if (!present) {
			System.out.println("   SKIPPING rest of VOLUME=" + label + " - not present (not a failure, just no "
					+ "card inserted / not applicable)");
			return;
		}
		check(label + ": FREE_BYTES (" + free + ") does not exceed TOTAL_BYTES (" + total + ")", free <= total);

		byte[] content = ("Hello from BSHMIProtocol StorageManualCheck at " + System.currentTimeMillis() + "\n")
				.getBytes(StandardCharsets.UTF_8);

		System.out.println("-> FILE_UPLOAD " + TEST_PATH + " (" + content.length + " bytes)");
		upload(client, volume, TEST_PATH, content);

		System.out.println("-> FILE_LIST_REQUEST \"/\" - expect " + TEST_PATH + " present with the right size");
		List<Entry> entries = list(client, volume, "/");
		Entry found = findByName(entries, TEST_PATH.substring(1));
		check(label + ": file appears in listing", found != null);
		if (found != null) {
			check(label + ": listed size matches (" + content.length + ")", found.size == content.length);
			check(label + ": listed as a file, not a directory", found.entryType == EntryType.FILE);
		}

		System.out.println("-> FILE_DOWNLOAD_REQUEST " + TEST_PATH + " - expect exact byte match");
		byte[] downloaded = download(client, volume, TEST_PATH);
		check(label + ": downloaded content matches uploaded content", java.util.Arrays.equals(content, downloaded));

		System.out.println("-> FILE_COPY " + TEST_PATH + " -> " + TEST_COPY_PATH + " (same volume)");
		copy(client, volume, TEST_PATH, volume, TEST_COPY_PATH);

		entries = list(client, volume, "/");
		check(label + ": source still present after copy", findByName(entries, TEST_PATH.substring(1)) != null);
		Entry copied = findByName(entries, TEST_COPY_PATH.substring(1));
		check(label + ": copy appears in listing", copied != null);
		if (copied != null) {
			check(label + ": copy size matches source", copied.size == content.length);
		}
		byte[] copiedContent = download(client, volume, TEST_COPY_PATH);
		check(label + ": copied content matches source content", java.util.Arrays.equals(content, copiedContent));

		System.out.println("-> FILE_RENAME " + TEST_COPY_PATH + " -> " + TEST_RENAME_PATH);
		rename(client, volume, TEST_COPY_PATH, TEST_RENAME_PATH);

		entries = list(client, volume, "/");
		check(label + ": old copy path gone after rename", findByName(entries, TEST_COPY_PATH.substring(1)) == null);
		check(label + ": renamed path present after rename",
				findByName(entries, TEST_RENAME_PATH.substring(1)) != null);

		System.out.println("-> FILE_RENAME " + TEST_PATH + " -> " + TEST_RENAME_PATH
				+ " (destination already exists - expect overwrite)");
		rename(client, volume, TEST_PATH, TEST_RENAME_PATH);

		entries = list(client, volume, "/");
		check(label + ": source gone after overwrite-rename", findByName(entries, TEST_PATH.substring(1)) == null);
		Entry overwritten = findByName(entries, TEST_RENAME_PATH.substring(1));
		check(label + ": destination present after overwrite-rename", overwritten != null);
		if (overwritten != null) {
			check(label + ": destination size now matches original source (overwrite happened)",
					overwritten.size == content.length);
		}
		byte[] finalContent = download(client, volume, TEST_RENAME_PATH);
		check(label + ": destination content now matches original source content",
				java.util.Arrays.equals(content, finalContent));

		System.out.println("-> FILE_DELETE " + TEST_RENAME_PATH + " (cleanup)");
		delete(client, volume, TEST_RENAME_PATH);

		System.out.println("-> FILE_LIST_REQUEST \"/\" again - expect no test files left");
		entries = list(client, volume, "/");
		check(label + ": no test files remain after cleanup",
				findByName(entries, TEST_PATH.substring(1)) == null
						&& findByName(entries, TEST_COPY_PATH.substring(1)) == null
						&& findByName(entries, TEST_RENAME_PATH.substring(1)) == null);
	}

	private static void testCrossVolumeCopy(CommandClient client) throws Exception {
		System.out.println("== FILE_COPY cross-volume (INTERNAL -> PSRAM) ==");
		byte[] content = ("Cross-volume copy check at " + System.currentTimeMillis() + "\n")
				.getBytes(StandardCharsets.UTF_8);

		System.out.println("-> FILE_UPLOAD " + CROSS_VOLUME_PATH + " on INTERNAL");
		upload(client, Volume.INTERNAL, CROSS_VOLUME_PATH, content);

		System.out.println("-> FILE_COPY " + CROSS_VOLUME_PATH + " INTERNAL -> PSRAM");
		copy(client, Volume.INTERNAL, CROSS_VOLUME_PATH, Volume.PSRAM, CROSS_VOLUME_PATH);

		List<Entry> psramEntries = list(client, Volume.PSRAM, "/");
		check("cross-volume: copy appears on PSRAM", findByName(psramEntries, CROSS_VOLUME_PATH.substring(1)) != null);

		byte[] copied = download(client, Volume.PSRAM, CROSS_VOLUME_PATH);
		check("cross-volume: PSRAM copy content matches INTERNAL source", java.util.Arrays.equals(content, copied));

		List<Entry> internalEntries = list(client, Volume.INTERNAL, "/");
		check("cross-volume: source still present on INTERNAL after copy",
				findByName(internalEntries, CROSS_VOLUME_PATH.substring(1)) != null);

		System.out.println("-> FILE_DELETE cleanup (both volumes)");
		delete(client, Volume.INTERNAL, CROSS_VOLUME_PATH);
		delete(client, Volume.PSRAM, CROSS_VOLUME_PATH);
	}

	private static void check(String label, boolean ok) {
		System.out.println("   [" + (ok ? "PASS" : "FAIL") + "] " + label);
		if (!ok) {
			failures++;
		}
	}

	private static final class Entry {
		final String name;
		final int entryType;
		final long size;

		Entry(String name, int entryType, long size) {
			this.name = name;
			this.entryType = entryType;
			this.size = size;
		}
	}

	private static Entry findByName(List<Entry> entries, String name) {
		for (Entry e : entries) {
			if (e.name.equals(name)) {
				return e;
			}
		}
		return null;
	}

	private static long[] storageInfo(CommandClient client, int volume) throws Exception {
		Frame response = client.send(CommandId.STORAGE_INFO_REQUEST, new byte[] { (byte) volume });
		byte[] data = response.getPayload();
		long present = data[1] & 0xFF;
		long total = readU32LE(data, 2);
		long free = readU32LE(data, 6);
		return new long[] { present, total, free };
	}

	private static List<Entry> list(CommandClient client, int volume, String path) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) volume);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		Frame response = client.send(CommandId.FILE_LIST_REQUEST, payload.array());
		byte[] data = response.getPayload();

		int count = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8);
		List<Entry> entries = new ArrayList<>(count);
		int pos = 2;
		for (int i = 0; i < count; i++) {
			int nameLen = data[pos] & 0xFF;
			pos += 1;
			String name = new String(data, pos, nameLen, StandardCharsets.UTF_8);
			pos += nameLen;
			int entryType = data[pos] & 0xFF;
			pos += 1;
			long size = readU32LE(data, pos);
			pos += 4;
			entries.add(new Entry(name, entryType, size));
		}
		return entries;
	}

	private static byte[] download(CommandClient client, int volume, String path) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) volume);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		Frame response = client.send(CommandId.FILE_DOWNLOAD_REQUEST, payload.array());
		byte[] data = response.getPayload();
		long fileLen = readU32LE(data, 0);
		byte[] out = new byte[(int) fileLen];
		System.arraycopy(data, 4, out, 0, (int) fileLen);
		return out;
	}

	private static void upload(CommandClient client, int volume, String path, byte[] content) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(volume);
		out.write(pathBytes.length);
		out.write(pathBytes);
		ByteBuffer lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		lenBuf.putInt(content.length);
		out.write(lenBuf.array());
		out.write(content);
		send(client, CommandId.FILE_UPLOAD, out.toByteArray());
	}

	private static void delete(CommandClient client, int volume, String path) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) volume);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		send(client, CommandId.FILE_DELETE, payload.array());
	}

	private static void copy(CommandClient client, int srcVolume, String srcPath, int dstVolume, String dstPath)
			throws Exception {
		byte[] srcPathBytes = srcPath.getBytes(StandardCharsets.UTF_8);
		byte[] dstPathBytes = dstPath.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(srcVolume);
		out.write(srcPathBytes.length);
		out.write(srcPathBytes);
		out.write(dstVolume);
		out.write(dstPathBytes.length);
		out.write(dstPathBytes);
		send(client, CommandId.FILE_COPY, out.toByteArray());
	}

	private static void rename(CommandClient client, int volume, String srcPath, String dstPath) throws Exception {
		byte[] srcPathBytes = srcPath.getBytes(StandardCharsets.UTF_8);
		byte[] dstPathBytes = dstPath.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(volume);
		out.write(srcPathBytes.length);
		out.write(srcPathBytes);
		out.write(dstPathBytes.length);
		out.write(dstPathBytes);
		send(client, CommandId.FILE_RENAME, out.toByteArray());
	}

	private static long readU32LE(byte[] data, int offset) {
		return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8) | ((data[offset + 2] & 0xFFL) << 16)
				| ((data[offset + 3] & 0xFFL) << 24);
	}

	private static void send(CommandClient client, int commandId, byte[] payload) throws Exception {
		try {
			Frame response = client.send(commandId, payload);
			System.out.println("   ACKed (0x" + Integer.toHexString(response.getCommandId()) + ")");
		} catch (CommandNackException e) {
			System.err.println("FAILED: commandId=0x" + Integer.toHexString(commandId) + " NACK status=0x"
					+ Integer.toHexString(e.getStatus()));
			System.exit(1);
		}
	}
}
