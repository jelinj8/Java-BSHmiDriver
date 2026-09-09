package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.DrawTextFlags;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.MacroCodec;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.TextAlign;
import cz.bliksoft.hmieink.protocol.TextBackground;
import cz.bliksoft.hmieink.protocol.Volume;

/**
 * Manual, real-hardware verification of DRAW_TEXT's FLAGS.TEXT_IS_PATH (doc/PROTOCOL.md §12.6) -
 * the whole point of the feature: a single saved macro that draws different text on different
 * plays, purely because a VOLUME=PSRAM file it reads from changed in between, with the macro file
 * itself never re-recorded or touched. Sequence: write "HELLO" to a PSRAM file, record a macro
 * containing one DRAW_TEXT with TEXT_IS_PATH set (referencing that file), save it, confirm (via
 * {@code MacroCodec}) the saved macro captured the *path*, not the resolved text, then play it
 * (expect "HELLO" on the panel), overwrite the PSRAM file with "WORLD", and play the exact same
 * saved macro again (expect "WORLD" this time). NOT part of the automated {@code mvn test} suite -
 * run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.ParametrizedMacroManualCheck COM5
 * </pre>
 */
public final class ParametrizedMacroManualCheck {

	private static final String LABEL_PATH = "/label.txt";
	private static final String MACRO_PATH = "/param_test.macro";
	private static final int TEXT_X = 20, TEXT_Y = 20;

	private static int failures = 0;

	private ParametrizedMacroManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: ParametrizedMacroManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			System.out.println("-> FILE_UPLOAD " + LABEL_PATH + " = \"HELLO\" to VOLUME=PSRAM");
			uploadLabel(client, "HELLO");

			System.out.println("-> RECORD_MACRO");
			send(client, CommandId.RECORD_MACRO, new byte[0]);

			byte[] drawTextPayload = drawTextByPathPayload(LABEL_PATH);
			System.out.println("-> DRAW_TEXT with FLAGS.TEXT_IS_PATH, TEXT=" + LABEL_PATH + " (recorded)");
			send(client, CommandId.DRAW_TEXT, drawTextPayload);

			System.out.println("-> SAVE_MACRO " + MACRO_PATH + " to VOLUME=INTERNAL");
			send(client, CommandId.SAVE_MACRO, saveMacroPayload(MACRO_PATH));

			System.out.println("-> FILE_DOWNLOAD_REQUEST " + MACRO_PATH + " - confirm it captured the PATH, not "
					+ "the resolved text");
			byte[] savedMacro = download(client, MACRO_PATH);
			List<MacroCodec.Entry> entries = MacroCodec.decode(savedMacro);
			check("recorded exactly 1 entry", entries.size() == 1);
			if (entries.size() == 1) {
				MacroCodec.Entry entry = entries.get(0);
				check("entry is DRAW_TEXT", entry.commandId == CommandId.DRAW_TEXT);
				String capturedText = extractDrawTextString(entry.payload);
				check("captured TEXT is the path \"" + LABEL_PATH + "\", not \"HELLO\"",
						LABEL_PATH.equals(capturedText));
			}

			System.out.println("-> PLAY_MACRO " + MACRO_PATH + " (1st time - PSRAM file currently says \"HELLO\")");
			send(client, CommandId.PLAY_MACRO, saveMacroPayload(MACRO_PATH));
			Thread.sleep(2000);

			System.out.println("-> FILE_UPLOAD " + LABEL_PATH + " = \"WORLD\" to VOLUME=PSRAM (overwrite, macro "
					+ "file itself untouched)");
			uploadLabel(client, "WORLD");

			System.out.println("-> PLAY_MACRO " + MACRO_PATH + " again (2nd time - same saved macro, PSRAM file "
					+ "now says \"WORLD\")");
			send(client, CommandId.PLAY_MACRO, saveMacroPayload(MACRO_PATH));
			Thread.sleep(2000);

			System.out.println("-> FILE_DELETE " + MACRO_PATH + " (cleanup, VOLUME=INTERNAL)");
			deleteFile(client, Volume.INTERNAL, MACRO_PATH);

			if (failures == 0) {
				System.out.println("PROGRAMMATIC CHECKS PASSED - now confirm visually: the panel should show "
						+ "\"WORLD\" now (the 2nd play's output), having shown \"HELLO\" after the 1st play - same "
						+ "saved macro, different text each time, purely from the PSRAM file changing in between");
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

	/** Parses a captured DRAW_TEXT payload (doc/PROTOCOL.md §12.6's 15-byte header) and returns its TEXT as a String. */
	private static String extractDrawTextString(byte[] drawTextPayload) {
		int textLen = (drawTextPayload[13] & 0xFF) | ((drawTextPayload[14] & 0xFF) << 8);
		return new String(drawTextPayload, 15, textLen, StandardCharsets.UTF_8);
	}

	private static byte[] drawTextByPathPayload(String path) {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(15 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) TEXT_X);
		payload.putShort((short) TEXT_Y);
		payload.putShort((short) 0); // WIDTH=0
		payload.put((byte) 0x00); // FONT_ID
		payload.put((byte) Color.BLACK);
		payload.put((byte) TextBackground.OPAQUE); // opaque so a shorter replay word fully covers a longer one
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) TextAlign.LEFT);
		payload.put((byte) 0); // WRAP
		payload.put((byte) (0x01 | DrawTextFlags.TEXT_IS_PATH)); // FLAGS: REFRESH_NOW | TEXT_IS_PATH
		payload.putShort((short) pathBytes.length);
		payload.put(pathBytes);
		return payload.array();
	}

	private static void uploadLabel(CommandClient client, String text) throws Exception {
		byte[] content = text.getBytes(StandardCharsets.UTF_8);
		byte[] pathBytes = LABEL_PATH.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload =
				ByteBuffer.allocate(2 + pathBytes.length + 4 + content.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.PSRAM);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		payload.putInt(content.length);
		payload.put(content);
		send(client, CommandId.FILE_UPLOAD, payload.array());
	}

	private static byte[] saveMacroPayload(String path) {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.INTERNAL);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		return payload.array();
	}

	private static void deleteFile(CommandClient client, int volume, String path) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) volume);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		send(client, CommandId.FILE_DELETE, payload.array());
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
