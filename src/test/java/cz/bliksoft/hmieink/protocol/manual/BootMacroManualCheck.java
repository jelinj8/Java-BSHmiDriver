package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.MacroCodec;
import cz.bliksoft.hmieink.protocol.ReadScreenMode;
import cz.bliksoft.hmieink.protocol.ReadScreenSource;
import cz.bliksoft.hmieink.protocol.RlePackBits;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.Volume;

/**
 * Manual, real-hardware verification of the boot macro (doc/PROTOCOL.md §0x0A00): uploads a
 * one-entry macro (a single DRAW_RECT) to {@code /boot.macro} on VOLUME=INTERNAL, closes the
 * connection, reconnects (which resets the board via the CH340 adapter's DTR/RTS lines, the same
 * mechanism every other manual check's own initial connect relies on), and confirms - via
 * READ_SCREEN, without ever sending a live PLAY_MACRO this run - that the rectangle appears anyway,
 * proving the boot macro was found and played automatically during {@code setup()}. Always deletes
 * {@code /boot.macro} afterward (even on failure), since leaving it behind would make every
 * subsequent manual check's own initial connect replay it too. NOT part of the automated
 * {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.BootMacroManualCheck COM5
 * </pre>
 */
public final class BootMacroManualCheck {

	private static final String BOOT_MACRO_PATH = "/boot.macro";
	private static final int RECT_X = 20, RECT_Y = 20, RECT_SIZE = 30;

	private static int failures = 0;

	private BootMacroManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: BootMacroManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		System.out.println("-> connecting (1st time) to upload the boot macro");
		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		client.connect();
		try {
			byte[] macro = MacroCodec.encode(Collections.singletonList(
					new MacroCodec.Entry(CommandId.DRAW_RECT, rectPayload(RECT_X, RECT_Y))));
			System.out.println("-> FILE_UPLOAD " + BOOT_MACRO_PATH + " to VOLUME=INTERNAL (" + macro.length
					+ " bytes)");
			upload(client, BOOT_MACRO_PATH, macro);
		} finally {
			client.close();
		}

		try {
			Thread.sleep(500); // let the OS fully release the port before reopening it
			System.out.println("-> reconnecting (this resets the board - if the boot macro works, the "
					+ "rectangle should appear with no PLAY_MACRO sent this run)");
			client = new CommandClient(new SerialFrameTransport(portDescriptor));
			client.connect();
			try {
				System.out.println("-> waiting for the boot macro to finish playing...");
				Thread.sleep(3000);

				System.out.println("-> READ_SCREEN - confirm the rectangle was drawn by the boot macro");
				boolean drawn = isBlackAt(client, RECT_X + RECT_SIZE / 2, RECT_Y + RECT_SIZE / 2);
				check("boot macro played automatically on cold start (no PLAY_MACRO sent this run)", drawn);
			} finally {
				System.out.println("-> FILE_DELETE " + BOOT_MACRO_PATH + " (cleanup, so future connects on this "
						+ "board don't keep replaying it)");
				delete(client, BOOT_MACRO_PATH);
				client.close();
			}

			if (failures == 0) {
				System.out.println("ALL CHECKS PASSED");
			} else {
				System.out.println(failures + " CHECK(S) FAILED - see above");
			}
		} finally {
			if (failures > 0) {
				System.exit(1);
			}
		}
	}

	private static void check(String label, boolean ok) {
		System.out.println("   [" + (ok ? "PASS" : "FAIL") + "] " + label);
		if (!ok) {
			failures++;
		}
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

	private static void upload(CommandClient client, String path, byte[] content) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload =
				ByteBuffer.allocate(2 + pathBytes.length + 4 + content.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.INTERNAL);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		payload.putInt(content.length);
		payload.put(content);
		send(client, CommandId.FILE_UPLOAD, payload.array());
	}

	private static void delete(CommandClient client, String path) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.INTERNAL);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		send(client, CommandId.FILE_DELETE, payload.array());
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
