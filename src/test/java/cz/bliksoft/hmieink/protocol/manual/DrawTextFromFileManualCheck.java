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
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.TextAlign;
import cz.bliksoft.hmieink.protocol.TextBackground;
import cz.bliksoft.hmieink.protocol.Volume;

/**
 * Manual, real-hardware verification of DRAW_TEXT's FLAGS.TEXT_IS_PATH volume-prefix convention
 * (doc/PROTOCOL.md §12.6): since DRAW_TEXT's own payload has no VOLUME field, the volume is instead
 * selected by an optional "R:"/"S:"/"F:" prefix on TEXT itself (PSRAM/SD/INTERNAL), stripped before
 * the rest is used as the path - no prefix defaults to PSRAM. Writes a distinct label to each
 * volume, then draws one line per volume using its prefix, plus a final line with no prefix at all
 * (should also read the PSRAM label, confirming the default). VOLUME=SD is skipped (with a
 * warning, not a failure) if STORAGE_INFO reports no card present. NOT part of the automated
 * {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.DrawTextFromFileManualCheck COM5
 * </pre>
 */
public final class DrawTextFromFileManualCheck {

	private DrawTextFromFileManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: DrawTextFromFileManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			System.out.println("-> FILE_UPLOAD /psram_label.txt = \"FROM-RAM\" to VOLUME=PSRAM");
			upload(client, Volume.PSRAM, "/psram_label.txt", "FROM-RAM");

			boolean sdPresent = isSdPresent(client);
			System.out.println("-> STORAGE_INFO_REQUEST VOLUME=SD - PRESENT=" + sdPresent);
			if (sdPresent) {
				System.out.println("-> FILE_UPLOAD /sd_label.txt = \"FROM-SD\" to VOLUME=SD");
				upload(client, Volume.SD, "/sd_label.txt", "FROM-SD");
			} else {
				System.out.println("   SKIPPING the S: prefix line - no SD card present (not a failure)");
			}

			System.out.println("-> FILE_UPLOAD /flash_label.txt = \"FROM-FLASH\" to VOLUME=INTERNAL");
			upload(client, Volume.INTERNAL, "/flash_label.txt", "FROM-FLASH");

			int y = 20;
			System.out.println("-> DRAW_TEXT TEXT=\"R:/psram_label.txt\" at y=" + y);
			drawTextByPath(client, y, "R:/psram_label.txt");
			y += 20;
			if (sdPresent) {
				System.out.println("-> DRAW_TEXT TEXT=\"S:/sd_label.txt\" at y=" + y);
				drawTextByPath(client, y, "S:/sd_label.txt");
				y += 20;
			}
			System.out.println("-> DRAW_TEXT TEXT=\"F:/flash_label.txt\" at y=" + y);
			drawTextByPath(client, y, "F:/flash_label.txt");
			y += 20;
			System.out.println("-> DRAW_TEXT TEXT=\"/psram_label.txt\" (no prefix - should default to PSRAM) at y="
					+ y);
			drawTextByPath(client, y, "/psram_label.txt");

			System.out.println("-> sending REFRESH(MODE=0x01)");
			try {
				Frame response = client.send(CommandId.REFRESH, new byte[] { 0x01 }, 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel: \"FROM-RAM\"" + (sdPresent ? ", \"FROM-SD\"" : "")
						+ ", \"FROM-FLASH\", then \"FROM-RAM\" again (the unprefixed line), one per line");
			} catch (CommandNackException e) {
				System.err.println("FAILED: REFRESH NACK status=0x" + Integer.toHexString(e.getStatus()));
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	private static boolean isSdPresent(CommandClient client) throws Exception {
		Frame response = client.send(CommandId.STORAGE_INFO_REQUEST, new byte[] { (byte) Volume.SD });
		return (response.getPayload()[1] & 0xFF) != 0;
	}

	private static void upload(CommandClient client, int volume, String path, String content) throws Exception {
		byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload =
				ByteBuffer.allocate(2 + pathBytes.length + 4 + contentBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) volume);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		payload.putInt(contentBytes.length);
		payload.put(contentBytes);
		send(client, CommandId.FILE_UPLOAD, payload.array());
	}

	private static void drawTextByPath(CommandClient client, int y, String pathWithPrefix) throws Exception {
		byte[] pathBytes = pathWithPrefix.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(15 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) 20); // X
		payload.putShort((short) y); // Y
		payload.putShort((short) 0); // WIDTH=0
		payload.put((byte) 0x00); // FONT_ID
		payload.put((byte) Color.BLACK);
		payload.put((byte) TextBackground.TRANSPARENT);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) TextAlign.LEFT);
		payload.put((byte) 0); // WRAP
		payload.put((byte) (0x01 | DrawTextFlags.TEXT_IS_PATH)); // FLAGS: REFRESH_NOW | TEXT_IS_PATH
		payload.putShort((short) pathBytes.length);
		payload.put(pathBytes);
		send(client, CommandId.DRAW_TEXT, payload.array());
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
