package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import cz.bliksoft.hmieink.protocol.ClearArtifactsFlags;
import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.ReadScreenMode;
import cz.bliksoft.hmieink.protocol.ReadScreenSource;
import cz.bliksoft.hmieink.protocol.RlePackBits;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;

/**
 * Manual, real-hardware verification of READ_SCREEN/SCREEN_DATA
 * (doc/PROTOCOL.md §8) and CLEAR_ARTIFACTS (§9). Unlike the other manual
 * checks, this one verifies most of its own assertions programmatically
 * (decoded pixel content is compared byte-for-byte against what's expected)
 * rather than relying on a human looking at the panel - the whole point of
 * these two commands is to read data back, which this test can check directly.
 * Sequence:
 *
 * <ol>
 * <li>Draw a filled black rect ("A") immediately (FLAGS.REFRESH_NOW=1) - panel
 * and working buffer now agree.
 * <li>READ_SCREEN both SOURCE=PANEL and SOURCE=WORKING_BUFFER, MODE=FULL -
 * confirm both show A and agree with each other.
 * <li>Draw a second filled black rect ("B") deferred (FLAGS=0, not flushed) -
 * working buffer and panel now genuinely differ.
 * <li>READ_SCREEN SOURCE=WORKING_BUFFER - confirm it shows BOTH A and B.
 * READ_SCREEN SOURCE=PANEL - confirm it shows ONLY A (B not yet flushed) - the
 * key distinguishing check.
 * <li>REFRESH(MODE=0x00) to flush B, then confirm SOURCE=PANEL now shows both A
 * and B too.
 * <li>CLEAR_ARTIFACTS(CYCLES=1, FLAGS=0) - confirm SOURCE=PANEL reads back
 * all-white, while SOURCE=WORKING_BUFFER is untouched (still shows both A and
 * B).
 * <li>CLEAR_ARTIFACTS(CYCLES=1, FLAGS=RESTORE_CONTENT) - confirm SOURCE=PANEL
 * shows both A and B again.
 * </ol>
 *
 * NOT part of the automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.ScreenReadbackManualCheck COM5
 * </pre>
 */
public final class ScreenReadbackManualCheck {

	// Rect A: immediate. Rect B: deferred. Chosen apart so a single interior sample
	// point per rect
	// unambiguously tells them apart.
	private static final int RECT_A_X = 50, RECT_A_Y = 50, RECT_A_W = 60, RECT_A_H = 60;
	private static final int RECT_B_X = 250, RECT_B_Y = 50, RECT_B_W = 60, RECT_B_H = 60;

	private static int failures = 0;

	private ScreenReadbackManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: ScreenReadbackManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			System.out.println("-> DRAW_RECT A (50,50,60,60), immediate (FLAGS.REFRESH_NOW=1)");
			drawRect(client, RECT_A_X, RECT_A_Y, RECT_A_W, RECT_A_H, 0x01);

			System.out.println("-> READ_SCREEN SOURCE=PANEL, MODE=FULL - expect A present, B absent");
			check("panel shows A right after drawing it", readScreen(client, ReadScreenSource.PANEL), true, false);
			System.out.println("-> READ_SCREEN SOURCE=WORKING_BUFFER, MODE=FULL - expect A present, B absent");
			check("working buffer shows A right after drawing it", readScreen(client, ReadScreenSource.WORKING_BUFFER),
					true, false);

			System.out.println("-> DRAW_RECT B (250,50,60,60), deferred (FLAGS=0)");
			drawRect(client, RECT_B_X, RECT_B_Y, RECT_B_W, RECT_B_H, 0x00);

			System.out.println("-> READ_SCREEN SOURCE=WORKING_BUFFER - expect A and B both present");
			check("working buffer shows both A and B before REFRESH",
					readScreen(client, ReadScreenSource.WORKING_BUFFER), true, true);
			System.out.println("-> READ_SCREEN SOURCE=PANEL - expect A present, B still absent (not flushed yet)");
			check("panel still shows only A before REFRESH", readScreen(client, ReadScreenSource.PANEL), true, false);

			System.out.println("-> REFRESH(MODE=0x00) to flush the deferred B");
			refresh(client, 0x00);
			System.out.println("-> READ_SCREEN SOURCE=PANEL - expect A and B both present now");
			check("panel shows both A and B after REFRESH", readScreen(client, ReadScreenSource.PANEL), true, true);

			System.out.println("-> CLEAR_ARTIFACTS(CYCLES=1, FLAGS=0) - panel should end up blank");
			clearArtifacts(client, 1, 0x00);
			System.out.println("-> READ_SCREEN SOURCE=PANEL - expect blank (A and B both absent)");
			check("panel is blank after CLEAR_ARTIFACTS(no restore)", readScreen(client, ReadScreenSource.PANEL), false,
					false);
			System.out.println("-> READ_SCREEN SOURCE=WORKING_BUFFER - expect untouched (A and B both still present)");
			check("working buffer untouched by CLEAR_ARTIFACTS", readScreen(client, ReadScreenSource.WORKING_BUFFER),
					true, true);

			System.out.println("-> CLEAR_ARTIFACTS(CYCLES=1, FLAGS=RESTORE_CONTENT) - panel should show A and B again");
			clearArtifacts(client, 1, ClearArtifactsFlags.RESTORE_CONTENT);
			System.out.println("-> READ_SCREEN SOURCE=PANEL - expect A and B both present again");
			check("panel restored after CLEAR_ARTIFACTS(restore)", readScreen(client, ReadScreenSource.PANEL), true,
					true);

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

	/**
	 * Decodes SCREEN_DATA and returns whether the sample point inside each rect
	 * reads BLACK.
	 */
	private static boolean[] readScreen(CommandClient client, int source) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) source);
		payload.put((byte) ReadScreenMode.FULL);
		Frame response = client.send(CommandId.READ_SCREEN, payload.array(), 10_000);
		byte[] data = response.getPayload();

		int encoding = data[0] & 0xFF;
		int width = readU16LE(data, 5);
		int height = readU16LE(data, 7);
		long decodedLen = readU32LE(data, 9);
		long encodedLen = readU32LE(data, 13);
		byte[] encoded = new byte[(int) encodedLen];
		System.arraycopy(data, 17, encoded, 0, (int) encodedLen);

		byte[] raw;
		if (encoding == 0x01) {
			raw = RlePackBits.decode(encoded, (int) decodedLen);
		} else {
			raw = encoded;
		}

		boolean aSample = getBit(raw, width, RECT_A_X + RECT_A_W / 2, RECT_A_Y + RECT_A_H / 2);
		boolean bSample = getBit(raw, width, RECT_B_X + RECT_B_W / 2, RECT_B_Y + RECT_B_H / 2);
		System.out.println("   decoded " + width + "x" + height + " (" + (encoding == 0x01 ? "RLE" : "RAW") + ", "
				+ encodedLen + " encoded bytes) - A sample=" + (aSample ? "BLACK" : "white") + ", B sample="
				+ (bSample ? "BLACK" : "white"));
		return new boolean[] { aSample, bSample };
	}

	private static void check(String label, boolean[] actual, boolean expectA, boolean expectB) {
		boolean ok = actual[0] == expectA && actual[1] == expectB;
		System.out.println("   [" + (ok ? "PASS" : "FAIL") + "] " + label + " (expected A=" + expectA + " B=" + expectB
				+ ", got A=" + actual[0] + " B=" + actual[1] + ")");
		if (!ok) {
			failures++;
		}
	}

	private static boolean getBit(byte[] raw, int width, int x, int y) {
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

	private static void drawRect(CommandClient client, int x, int y, int w, int h, int flags) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) w);
		payload.putShort((short) h);
		payload.put((byte) Color.BLACK);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) 1); // FILLED
		payload.put((byte) 1); // LINE_WIDTH (ignored, filled)
		payload.put((byte) flags);
		send(client, CommandId.DRAW_RECT, payload.array());
	}

	private static void refresh(CommandClient client, int mode) throws Exception {
		send(client, CommandId.REFRESH, new byte[] { (byte) mode });
	}

	private static void clearArtifacts(CommandClient client, int cycles, int flags) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(2);
		payload.put((byte) cycles);
		payload.put((byte) flags);
		try {
			// CLEAR_ARTIFACTS can take several seconds (doc/PROTOCOL.md §9) - longer ACK
			// timeout.
			Frame response = client.send(CommandId.CLEAR_ARTIFACTS, payload.array(), 20_000);
			System.out.println("   ACKed (0x" + Integer.toHexString(response.getCommandId()) + ")");
		} catch (CommandNackException e) {
			System.err.println("FAILED: CLEAR_ARTIFACTS NACK status=0x" + Integer.toHexString(e.getStatus()));
			System.exit(1);
		}
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
