package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.DrawTextFlags;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.ReadScreenMode;
import cz.bliksoft.hmieink.protocol.ReadScreenSource;
import cz.bliksoft.hmieink.protocol.RlePackBits;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.Status;
import cz.bliksoft.hmieink.protocol.TextAlign;
import cz.bliksoft.hmieink.protocol.TextBackground;
import cz.bliksoft.hmieink.protocol.Volume;
import cz.bliksoft.hmieink.protocol.WriteFlags;

/**
 * Manual, real-hardware verification of DRAW_TEXT's FLAGS.MISSING_FILE_TOLERANT
 * bit (doc/PROTOCOL.md §12.6, only meaningful together with TEXT_IS_PATH): a
 * TEXT_IS_PATH draw referencing a path that doesn't exist should
 * NACK(FILE_NOT_FOUND) when the tolerant bit is unset (today's existing
 * behavior - a positive control, since this bit is new), and ACK with nothing
 * drawn when the tolerant bit is set. A third, genuinely positive control
 * (TEXT_IS_PATH against a file that *does* exist) proves the READ_SCREEN pixel
 * check used to confirm "nothing drawn" actually detects a real draw when one
 * happens, rather than always reading back blank. NOT part of the automated
 * {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.MissingFileToleranceManualCheck COM5
 * </pre>
 */
public final class MissingFileToleranceManualCheck {

	private static final String PRESENT_PATH = "/present_label.txt";
	private static final String MISSING_PATH = "/definitely_missing_label.txt";
	private static final int X = 20;
	private static final int REGION_WIDTH = 200;
	private static final int REGION_HEIGHT = 20;

	private static int failures = 0;

	private MissingFileToleranceManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: MissingFileToleranceManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test)...");
		client.connect();
		try {
			System.out.println("-> FILE_UPLOAD " + PRESENT_PATH + " = \"OK\" to VOLUME=PSRAM");
			upload(client, PRESENT_PATH, "OK");

			int y = 150;
			System.out.println("-- control: TEXT_IS_PATH against an existing file should ACK and actually draw --");
			clearRegion(client, y);
			drawTextByPath(client, y, PRESENT_PATH, DrawTextFlags.TEXT_IS_PATH);
			check("existing-file TEXT_IS_PATH draws visible pixels", isAnyBlack(client, y));

			System.out.println("-- unset MISSING_FILE_TOLERANT: missing file should NACK(FILE_NOT_FOUND) --");
			clearRegion(client, y);
			int status = sendExpectNack(client, drawTextPayload(y, MISSING_PATH, DrawTextFlags.TEXT_IS_PATH));
			check("missing file without MISSING_FILE_TOLERANT NACKs", status == Status.FILE_NOT_FOUND);
			check("missing file without MISSING_FILE_TOLERANT draws nothing", !isAnyBlack(client, y));

			System.out.println("-- set MISSING_FILE_TOLERANT: missing file should ACK and draw nothing --");
			clearRegion(client, y);
			drawTextByPath(client, y, MISSING_PATH, DrawTextFlags.TEXT_IS_PATH | DrawTextFlags.MISSING_FILE_TOLERANT);
			check("missing file with MISSING_FILE_TOLERANT draws nothing", !isAnyBlack(client, y));

			if (failures == 0) {
				System.out.println("ALL CHECKS PASSED");
			} else {
				System.out.println(failures + " CHECK(S) FAILED - see above");
			}
		} finally {
			client.close();
		}
		if (failures > 0) {
			System.exit(1);
		}
	}

	private static void check(String label, boolean ok) {
		System.out.println("   [" + (ok ? "PASS" : "FAIL") + "] " + label);
		if (!ok) {
			failures++;
		}
	}

	private static void upload(CommandClient client, String path, String content) throws Exception {
		byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length + 4 + contentBytes.length)
				.order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.PSRAM);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		payload.putInt(contentBytes.length);
		payload.put(contentBytes);
		send(client, CommandId.FILE_UPLOAD, payload.array());
	}

	private static void clearRegion(CommandClient client, int y) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) X);
		payload.putShort((short) y);
		payload.putShort((short) REGION_WIDTH);
		payload.putShort((short) REGION_HEIGHT);
		payload.put((byte) Color.WHITE);
		payload.put((byte) WriteFlags.REFRESH_NOW);
		send(client, CommandId.CLEAR_REGION, payload.array());
	}

	private static byte[] drawTextPayload(int y, String pathWithPrefix, int flags) {
		byte[] pathBytes = pathWithPrefix.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(15 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) X);
		payload.putShort((short) y);
		payload.putShort((short) 0); // WIDTH=0
		payload.put((byte) 0x00); // FONT_ID
		payload.put((byte) Color.BLACK);
		payload.put((byte) TextBackground.TRANSPARENT);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) TextAlign.LEFT);
		payload.put((byte) 0); // WRAP
		payload.put((byte) (WriteFlags.REFRESH_NOW | flags));
		payload.putShort((short) pathBytes.length);
		payload.put(pathBytes);
		return payload.array();
	}

	private static void drawTextByPath(CommandClient client, int y, String pathWithPrefix, int flags) throws Exception {
		send(client, CommandId.DRAW_TEXT, drawTextPayload(y, pathWithPrefix, flags));
	}

	/**
	 * Returns any pixel in the X/y region is black, i.e. something was drawn there.
	 */
	private static boolean isAnyBlack(CommandClient client, int y) throws Exception {
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
		for (int row = y; row < y + REGION_HEIGHT; row++) {
			for (int col = X; col < X + REGION_WIDTH; col++) {
				int byteIndex = row * bytesPerRow + col / 8;
				int mask = 0x80 >> (col % 8);
				if ((raw[byteIndex] & mask) != 0) {
					return true;
				}
			}
		}
		return false;
	}

	private static int readU16LE(byte[] data, int offset) {
		return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
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

	/**
	 * Like {@link #send}, but expects (and returns the status of) a NACK rather
	 * than treating one as failure.
	 */
	private static int sendExpectNack(CommandClient client, byte[] payload) throws Exception {
		try {
			Frame response = client.send(CommandId.DRAW_TEXT, payload);
			System.err.println(
					"FAILED: expected NACK but got ACK (0x" + Integer.toHexString(response.getCommandId()) + ")");
			failures++;
			return -1;
		} catch (CommandNackException e) {
			System.out.println("   NACKed as expected (status=0x" + Integer.toHexString(e.getStatus()) + ")");
			return e.getStatus();
		}
	}
}
