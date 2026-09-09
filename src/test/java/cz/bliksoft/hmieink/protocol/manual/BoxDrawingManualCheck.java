package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.TextAlign;
import cz.bliksoft.hmieink.protocol.TextBackground;

/**
 * Manual, real-hardware verification of DRAW_TEXT's CP437 box-drawing support
 * (doc/PROTOCOL.md §12.6, {@code EmbeddedFont.h}'s {@code kBoxDrawingGlyphs}): draws a small
 * 2x2 ASCII-art table using real Unicode box-drawing characters (corners, tees, cross), sent as a
 * single pre-formatted multi-line DRAW_TEXT (WIDTH=0, embedded '\n' between rows) - proving both
 * the box-drawing glyph mapping and that fixed internal spacing survives untouched (WIDTH=0 never
 * word-wraps, so the table's alignment isn't disturbed). NOT part of the automated
 * {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.BoxDrawingManualCheck COM5
 * </pre>
 */
public final class BoxDrawingManualCheck {

	private BoxDrawingManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: BoxDrawingManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		// clang-format off
		String table =
				"┌─────┬─────┐\n"
				+ "│  A  │  B  │\n"
				+ "├─────┼─────┤\n"
				+ "│  C  │  D  │\n"
				+ "└─────┴─────┘";
		// clang-format on

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			System.out.println("-> DRAW_TEXT: 2x2 box-drawing table, WIDTH=0 (no word-wrap - "
					+ "spacing must survive exactly), immediate full refresh");
			byte[] textBytes = table.getBytes(StandardCharsets.UTF_8);
			ByteBuffer payload = ByteBuffer.allocate(15 + textBytes.length).order(ByteOrder.LITTLE_ENDIAN);
			payload.putShort((short) 20); // X
			payload.putShort((short) 20); // Y
			payload.putShort((short) 0); // WIDTH=0
			payload.put((byte) 0x00); // FONT_ID
			payload.put((byte) Color.BLACK);
			payload.put((byte) TextBackground.TRANSPARENT);
			payload.put((byte) DrawMode.REPLACE);
			payload.put((byte) TextAlign.LEFT);
			payload.put((byte) 0); // WRAP: irrelevant at WIDTH=0
			payload.put((byte) (0x01 | 0x02)); // FLAGS: REFRESH_NOW | REFRESH_FULL
			payload.putShort((short) textBytes.length);
			payload.put(textBytes);

			try {
				Frame response = client.send(CommandId.DRAW_TEXT, payload.array(), 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel for a 2x2 table with real box-drawing borders "
						+ "(corners, tees, cross) and cells labeled A/B/C/D");
			} catch (CommandNackException e) {
				System.err.println("FAILED: DRAW_TEXT NACK status=0x" + Integer.toHexString(e.getStatus()));
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}
}
