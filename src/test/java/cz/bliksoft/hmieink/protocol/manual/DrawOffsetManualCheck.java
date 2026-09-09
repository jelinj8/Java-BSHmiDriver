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
 * Manual, real-hardware verification of SET_DRAW_OFFSET (doc/PROTOCOL.md §12.13): draws the same
 * filled rectangle three times at the exact same wire coordinates, changing only the persistent
 * draw offset in between - confirming it pans every subsequent write, including one large enough
 * negative offset to straddle the left panel edge (only the surviving right portion should be
 * visible, flush against x=0) - then resets the offset and confirms normal drawing resumes. NOT
 * part of the automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.DrawOffsetManualCheck COM5
 * </pre>
 */
public final class DrawOffsetManualCheck {

	private static final int RECT_X = 20;
	private static final int RECT_Y = 150;
	private static final int RECT_W = 30;
	private static final int RECT_H = 30;

	private DrawOffsetManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: DrawOffsetManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			System.out.println("-> SET_DRAW_OFFSET(0,0) [identity] then DRAW_RECT at (20,150,30,30) - baseline");
			setDrawOffset(client, 0, 0);
			drawRect(client, RECT_X, RECT_Y, RECT_W, RECT_H);

			System.out.println(
					"-> SET_DRAW_OFFSET(60,0) then the SAME DRAW_RECT - should land 60px to the right of the baseline");
			setDrawOffset(client, 60, 0);
			drawRect(client, RECT_X, RECT_Y, RECT_W, RECT_H);

			System.out.println("-> SET_DRAW_OFFSET(-30,0) then the SAME DRAW_RECT - logical x becomes -10, so only "
					+ "the rightmost 20px should be visible, flush against the left panel edge");
			setDrawOffset(client, -30, 0);
			drawRect(client, RECT_X, RECT_Y, RECT_W, RECT_H);

			System.out.println("-> SET_DRAW_OFFSET(0,0) [reset] then DRAW_TEXT confirming normal drawing resumes");
			setDrawOffset(client, 0, 0);
			drawText(client, RECT_X, RECT_Y + 50, "Offset reset - back to normal");

			System.out.println("-> sending REFRESH(MODE=0x01)");
			try {
				Frame response = client.send(CommandId.REFRESH, new byte[] { 0x01 }, 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel for what actually appears in that row");
			} catch (CommandNackException e) {
				System.err.println("FAILED: REFRESH NACK status=0x" + Integer.toHexString(e.getStatus()));
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	private static void setDrawOffset(CommandClient client, int dx, int dy) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) dx);
		payload.putShort((short) dy);
		send(client, CommandId.SET_DRAW_OFFSET, payload.array());
	}

	private static void drawRect(CommandClient client, int x, int y, int w, int h) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) w);
		payload.putShort((short) h);
		payload.put((byte) Color.BLACK);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) 1); // FILLED
		payload.put((byte) 1); // LINE_WIDTH (ignored, filled)
		payload.put((byte) 0x00); // FLAGS: deferred
		send(client, CommandId.DRAW_RECT, payload.array());
	}

	private static void drawText(CommandClient client, int x, int y, String text) throws Exception {
		byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(15 + textBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) 0); // WIDTH=0
		payload.put((byte) 0x00); // FONT_ID
		payload.put((byte) Color.BLACK);
		payload.put((byte) TextBackground.TRANSPARENT);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) TextAlign.LEFT);
		payload.put((byte) 0); // WRAP
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
