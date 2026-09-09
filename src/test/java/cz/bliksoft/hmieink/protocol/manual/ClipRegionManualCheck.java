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
import cz.bliksoft.hmieink.protocol.ShiftDirection;
import cz.bliksoft.hmieink.protocol.TextAlign;
import cz.bliksoft.hmieink.protocol.TextBackground;

/**
 * Manual, real-hardware verification of SET_CLIP_REGION (doc/PROTOCOL.md §12.10) and its
 * interaction with SHIFT_REGION/DRAW_TEXT - the "scrolling log panel" scenario: draws a border
 * 2px *outside* where the clip box will be (before the clip is active, so the border itself isn't
 * clipped - and outside the exact box, so SHIFT_REGION's own fill can never overwrite the border's
 * pixels, which would otherwise happen since the border would sit exactly on the shifted
 * rectangle's own boundary), then sets the clip to the box, draws a line of text deliberately much
 * longer than the box - which should be truncated at the box edges, not spill outside it - shifts
 * the box's content up (scrolling), draws a second line of text the same way, then resets the clip
 * to the full panel and confirms drawing outside the old box works again. NOT part of the
 * automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.ClipRegionManualCheck COM5
 * </pre>
 */
public final class ClipRegionManualCheck {

	private static final int BOX_X = 50;
	private static final int BOX_Y = 50;
	private static final int BOX_WIDTH = 150;
	private static final int BOX_HEIGHT = 60;

	private ClipRegionManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: ClipRegionManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			System.out.println("-> DRAW_RECT outline showing the clip box (drawn before the clip is "
					+ "active, and 2px *outside* the actual box, so SHIFT_REGION's own fill never "
					+ "touches these border pixels - they sit outside the exact rectangle being "
					+ "scrolled/clipped)");
			drawRectOutline(client, BOX_X - 2, BOX_Y - 2, BOX_WIDTH + 4, BOX_HEIGHT + 4);

			System.out.println("-> SET_CLIP_REGION to the box - everything below should stay inside it");
			setClipRegion(client, BOX_X, BOX_Y, BOX_WIDTH, BOX_HEIGHT);

			System.out.println(
					"-> DRAW_TEXT starting at the box's left edge, deliberately much longer than the box");
			drawText(client, BOX_X, BOX_Y + 10, "This line is much longer than the little box");

			System.out.println("-> SHIFT_REGION: the box's content, UP, step=12, white fill (scroll)");
			shiftUp(client, BOX_X, BOX_Y, BOX_WIDTH, BOX_HEIGHT, 12);

			System.out.println("-> DRAW_TEXT again near the box's bottom, same overflow situation");
			drawText(client, BOX_X, BOX_Y + BOX_HEIGHT - 20, "Second scrolled-in line also overflows");

			System.out.println("-> SET_CLIP_REGION(0,0,0,0) - resets the clip to the full panel");
			setClipRegion(client, 0, 0, 0, 0);

			System.out.println("-> DRAW_TEXT well outside the old box - should render normally now");
			drawText(client, 10, 250, "Clip reset - full panel again");

			System.out.println("-> sending REFRESH(MODE=0x01)");
			try {
				Frame response = client.send(CommandId.REFRESH, new byte[] { 0x01 }, 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel: both text lines inside the box should be truncated at "
						+ "its right edge (never spilling past the drawn border), and the bottom line "
						+ "should render fully, outside any box");
			} catch (CommandNackException e) {
				System.err.println("FAILED: REFRESH NACK status=0x" + Integer.toHexString(e.getStatus()));
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	private static void drawRectOutline(CommandClient client, int x, int y, int w, int h) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) w);
		payload.putShort((short) h);
		payload.put((byte) Color.BLACK);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) 0); // FILLED = false (outline)
		payload.put((byte) 1); // LINE_WIDTH
		payload.put((byte) (0x01)); // FLAGS: REFRESH_NOW, fast partial - just to show the border early
		send(client, CommandId.DRAW_RECT, payload.array());
	}

	private static void setClipRegion(CommandClient client, int x, int y, int w, int h) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) w);
		payload.putShort((short) h);
		send(client, CommandId.SET_CLIP_REGION, payload.array());
	}

	private static void shiftUp(CommandClient client, int x, int y, int w, int h, int step) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) w);
		payload.putShort((short) h);
		payload.put((byte) ShiftDirection.UP);
		payload.putShort((short) step);
		payload.put((byte) Color.WHITE);
		payload.put((byte) 0x00); // FLAGS: deferred
		send(client, CommandId.SHIFT_REGION, payload.array());
	}

	private static void drawText(CommandClient client, int x, int y, String text) throws Exception {
		byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(15 + textBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) 0); // WIDTH=0: unbounded single line (clip region does the limiting)
		payload.put((byte) 0x00); // FONT_ID
		payload.put((byte) Color.BLACK);
		payload.put((byte) TextBackground.TRANSPARENT);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) TextAlign.LEFT);
		payload.put((byte) 0); // WRAP: not used with WIDTH=0
		payload.put((byte) 0x00); // FLAGS: deferred
		payload.putShort((short) textBytes.length);
		payload.put(textBytes);
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
