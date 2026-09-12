package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;

/**
 * Manual, real-hardware verification of the local drawing primitives
 * (doc/PROTOCOL.md §12.2-§12.5) - plan.md's Phase 2: sends a filled rect, an
 * XOR-overlapping rect (proving per-pixel compositing, not just REPLACE), an
 * outline circle, a filled circle, and a thick diagonal line, all deferred
 * (FLAGS.REFRESH_NOW=0), then a single REFRESH(MODE=0x01) to show everything at
 * once. NOT part of the automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.DrawPrimitivesManualCheck COM5
 * </pre>
 */
public final class DrawPrimitivesManualCheck {

	private DrawPrimitivesManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: DrawPrimitivesManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			System.out.println("-> DRAW_RECT filled black (20,20,100,60), REPLACE, deferred");
			drawRect(client, 20, 20, 100, 60, DrawMode.REPLACE, true, 1);

			System.out.println("-> DRAW_RECT filled black (60,40,80,60), XOR, deferred - overlap with the first rect "
					+ "should turn white");
			drawRect(client, 60, 40, 80, 60, DrawMode.XOR, true, 1);

			System.out.println("-> DRAW_CIRCLE outline black, center (300,80) r=50, width=4, deferred");
			drawCircle(client, 300, 80, 50, DrawMode.REPLACE, false, 4);

			System.out.println("-> DRAW_CIRCLE filled black, center (300,200) r=40, deferred");
			drawCircle(client, 300, 200, 40, DrawMode.REPLACE, true, 1);

			System.out.println("-> DRAW_LINE thick black (20,150)-(150,280), width=5, deferred");
			drawLine(client, 20, 150, 150, 280, DrawMode.REPLACE, 5);

			System.out.println("-> sending REFRESH(MODE=0x01) - everything above should appear together now");
			try {
				Frame response = client.send(CommandId.REFRESH, new byte[] { 0x01 }, 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel: a black rect with a white XOR-cutout square, an outline "
						+ "circle, a filled circle, and a thick diagonal line");
			} catch (CommandNackException e) {
				System.err.println("FAILED: REFRESH NACK status=0x" + Integer.toHexString(e.getStatus()));
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	private static void drawRect(CommandClient client, int x, int y, int w, int h, int drawMode, boolean filled,
			int lineWidth) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) w);
		payload.putShort((short) h);
		payload.put((byte) Color.BLACK);
		payload.put((byte) drawMode);
		payload.put((byte) (filled ? 1 : 0));
		payload.put((byte) lineWidth);
		payload.put((byte) 0x00); // FLAGS: deferred
		send(client, CommandId.DRAW_RECT, payload.array());
	}

	private static void drawCircle(CommandClient client, int centerX, int centerY, int radius, int drawMode,
			boolean filled, int lineWidth) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) centerX);
		payload.putShort((short) centerY);
		payload.putShort((short) radius);
		payload.put((byte) Color.BLACK);
		payload.put((byte) drawMode);
		payload.put((byte) (filled ? 1 : 0));
		payload.put((byte) lineWidth);
		payload.put((byte) 0x00); // FLAGS: deferred
		send(client, CommandId.DRAW_CIRCLE, payload.array());
	}

	private static void drawLine(CommandClient client, int x0, int y0, int x1, int y1, int drawMode, int lineWidth)
			throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x0);
		payload.putShort((short) y0);
		payload.putShort((short) x1);
		payload.putShort((short) y1);
		payload.put((byte) Color.BLACK);
		payload.put((byte) drawMode);
		payload.put((byte) lineWidth);
		payload.put((byte) 0x00); // FLAGS: deferred
		send(client, CommandId.DRAW_LINE, payload.array());
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
