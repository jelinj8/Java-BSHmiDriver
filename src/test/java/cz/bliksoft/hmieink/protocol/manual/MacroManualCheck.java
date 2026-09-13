package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.macro.MacroCodec;
import cz.bliksoft.hmieink.protocol.ReadScreenMode;
import cz.bliksoft.hmieink.protocol.ReadScreenSource;
import cz.bliksoft.hmieink.protocol.RlePackBits;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.Volume;

/**
 * Manual, real-hardware verification of
 * RECORD_MACRO/SAVE_MACRO/PLAY_MACRO/PAUSE (doc/PROTOCOL.md §0x0A00). Records
 * two DRAW_RECTs with a 500ms PAUSE between them, saves to VOLUME=INTERNAL,
 * downloads the saved file and confirms (via {@code MacroCodec}) it captured
 * exactly the three entries sent - byte-for-byte, not just "some file got
 * created". Then plays it back, checking two things that can't be seen by eye:
 * that PLAY_MACRO's ACK arrives immediately (it means "started", not
 * "finished") and that a live command sent right after still gets a prompt
 * response (proving the device stays responsive to live traffic while a macro's
 * PAUSE is in progress, rather than blocking). Finally waits out the macro and
 * reads the panel back (READ_SCREEN) to confirm both rectangles actually got
 * drawn. NOT part of the automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.MacroManualCheck COM5
 * </pre>
 */
public final class MacroManualCheck {

	private static final String MACRO_PATH = "/test.macro";
	private static final int RECT_A_X = 20, RECT_A_Y = 20, RECT_SIZE = 30;
	private static final int RECT_B_X = 100, RECT_B_Y = 20;
	// Long enough that, even after entry 0's own physical partial-refresh finishes
	// blocking loop()
	// (a live REFRESH_NOW draw always does this, macro or not - unrelated to PAUSE
	// itself), there's
	// still a wide, safely-inside-the-pause window left to land the live-command
	// probe in.
	private static final int PAUSE_MS = 1500;
	private static final long SETTLE_BEFORE_PROBE_MS = 900;
	private static final long RESPONSIVE_THRESHOLD_MS = 300;

	private static int failures = 0;

	private MacroManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: MacroManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			byte[] rectAPayload = rectPayload(RECT_A_X, RECT_A_Y);
			byte[] pausePayload = pausePayload(PAUSE_MS);
			byte[] rectBPayload = rectPayload(RECT_B_X, RECT_B_Y);

			System.out.println("-> RECORD_MACRO");
			send(client, CommandId.RECORD_MACRO, new byte[0]);

			System.out.println("-> DRAW_RECT A (recorded)");
			send(client, CommandId.DRAW_RECT, rectAPayload);
			System.out.println("-> PAUSE " + PAUSE_MS + "ms (recorded)");
			send(client, CommandId.PAUSE, pausePayload);
			System.out.println("-> DRAW_RECT B (recorded)");
			send(client, CommandId.DRAW_RECT, rectBPayload);

			System.out.println("-> SAVE_MACRO " + MACRO_PATH + " to VOLUME=INTERNAL");
			send(client, CommandId.SAVE_MACRO, saveMacroPayload(MACRO_PATH));

			System.out.println("-> FILE_DOWNLOAD_REQUEST " + MACRO_PATH + " - confirm exact recorded content");
			byte[] savedMacro = download(client, MACRO_PATH);
			List<MacroCodec.Entry> entries = MacroCodec.decode(savedMacro);
			check("recorded exactly 3 entries", entries.size() == 3);
			if (entries.size() == 3) {
				checkEntry("entry 0 is DRAW_RECT A", entries.get(0), CommandId.DRAW_RECT, rectAPayload);
				checkEntry("entry 1 is PAUSE", entries.get(1), CommandId.PAUSE, pausePayload);
				checkEntry("entry 2 is DRAW_RECT B", entries.get(2), CommandId.DRAW_RECT, rectBPayload);
			}

			System.out.println("-> PLAY_MACRO " + MACRO_PATH + " - timing its ACK");
			long playAckMs = timedSend(client, CommandId.PLAY_MACRO, playMacroPayload(MACRO_PATH));
			System.out.println("   PLAY_MACRO ACKed in " + playAckMs + "ms");
			check("PLAY_MACRO ACKs promptly (means \"started\", not \"finished\")",
					playAckMs < RESPONSIVE_THRESHOLD_MS);

			System.out.println("-> waiting " + SETTLE_BEFORE_PROBE_MS + "ms for entry 0's own physical partial-"
					+ "refresh to finish (a live REFRESH_NOW draw always blocks loop() for this, unrelated to "
					+ "PAUSE) so the next probe genuinely lands inside the PAUSE window, not mid-refresh");
			Thread.sleep(SETTLE_BEFORE_PROBE_MS);

			System.out.println("-> sending a live HANDSHAKE_REQUEST now - should still be prompt even though the "
					+ "macro's PAUSE should be in progress");
			// PIN_TYPE=NONE, PIN_LEN=0 (doc/PROTOCOL.md §5.3) - no pin offered.
			long handshakeMs = timedSend(client, CommandId.HANDSHAKE_REQUEST, new byte[] { 0, 0 });
			System.out.println("   HANDSHAKE_REQUEST responded in " + handshakeMs + "ms");
			check("device stays responsive to live commands during a macro PAUSE",
					handshakeMs < RESPONSIVE_THRESHOLD_MS);

			System.out.println("-> waiting for macro playback to finish...");
			Thread.sleep(PAUSE_MS + 3000);

			System.out.println("-> READ_SCREEN - confirm both rectangles were actually drawn");
			boolean aDrawn = isBlackAt(client, RECT_A_X + RECT_SIZE / 2, RECT_A_Y + RECT_SIZE / 2);
			boolean bDrawn = isBlackAt(client, RECT_B_X + RECT_SIZE / 2, RECT_B_Y + RECT_SIZE / 2);
			check("rectangle A was drawn by macro playback", aDrawn);
			check("rectangle B was drawn by macro playback", bDrawn);

			System.out.println("-> FILE_DELETE " + MACRO_PATH + " (cleanup)");
			send(client, CommandId.FILE_DELETE, deletePayload(MACRO_PATH));

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

	private static void check(String label, boolean ok) {
		System.out.println("   [" + (ok ? "PASS" : "FAIL") + "] " + label);
		if (!ok) {
			failures++;
		}
	}

	private static void checkEntry(String label, MacroCodec.Entry entry, int expectedCommandId,
			byte[] expectedPayload) {
		boolean ok = entry.commandId == expectedCommandId && Arrays.equals(entry.payload, expectedPayload);
		check(label, ok);
	}

	private static byte[] rectPayload(int x, int y) {
		ByteBuffer payload = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) RECT_SIZE);
		payload.putShort((short) RECT_SIZE);
		payload.put((byte) Color.BLACK);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) 1); // FILLED
		payload.put((byte) 1); // LINE_WIDTH (ignored, filled)
		payload.put((byte) 0x01); // FLAGS: REFRESH_NOW, fast partial
		return payload.array();
	}

	private static byte[] pausePayload(int durationMs) {
		ByteBuffer payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		payload.putInt(durationMs);
		return payload.array();
	}

	private static byte[] saveMacroPayload(String path) {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.INTERNAL);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		return payload.array();
	}

	private static byte[] playMacroPayload(String path) {
		return saveMacroPayload(path); // same VOLUME+PATH_LEN+PATH shape
	}

	private static byte[] deletePayload(String path) {
		return saveMacroPayload(path); // same VOLUME+PATH_LEN+PATH shape
	}

	private static byte[] download(CommandClient client, String path) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.INTERNAL);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		Frame response = client.send(CommandId.FILE_DOWNLOAD_REQUEST, payload.array());
		byte[] data = response.getPayload();
		long fileLen = readU32LE(data, 0);
		byte[] out = new byte[(int) fileLen];
		System.arraycopy(data, 4, out, 0, (int) fileLen);
		return out;
	}

	private static boolean isBlackAt(CommandClient client, int x, int y) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) ReadScreenSource.PANEL);
		payload.put((byte) ReadScreenMode.FULL);
		Frame response = client.send(CommandId.READ_SCREEN, payload.array(), 10_000);
		byte[] data = response.getPayload();
		int encoding = data[0] & 0xFF;
		int width = readU16LE(data, 5);
		long decodedLen = readU32LE(data, 9);
		long encodedLen = readU32LE(data, 13);
		byte[] encoded = new byte[(int) encodedLen];
		System.arraycopy(data, 17, encoded, 0, (int) encodedLen);
		byte[] raw = encoding == 0x01 ? RlePackBits.decode(encoded, (int) decodedLen) : encoded;
		int bytesPerRow = (width + 7) / 8;
		int byteIndex = y * bytesPerRow + x / 8;
		int mask = 0x80 >> (x % 8);
		return (raw[byteIndex] & mask) != 0;
	}

	private static int readU16LE(byte[] data, int offset) {
		return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
	}

	private static long readU32LE(byte[] data, int offset) {
		return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8) | ((data[offset + 2] & 0xFFL) << 16)
				| ((data[offset + 3] & 0xFFL) << 24);
	}

	private static long timedSend(CommandClient client, int commandId, byte[] payload) throws Exception {
		long start = System.currentTimeMillis();
		send(client, commandId, payload);
		return System.currentTimeMillis() - start;
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
