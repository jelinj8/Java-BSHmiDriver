package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.ShiftDirection;

/**
 * Manual, real-hardware verification of SHIFT_REGION (doc/PROTOCOL.md §12.9):
 * draws four black vertical stripes at X=0/100/200/300, refreshes to show them,
 * then shifts the whole panel LEFT by 50px with a white fill and refreshes
 * again. Each surviving stripe should visibly move 50px to the left (the stripe
 * at X=0 should vanish off the left edge, and a new white strip should appear
 * at the right edge) - proving content moved without being resent, and the
 * vacated area was correctly filled. NOT part of the automated {@code mvn test}
 * suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.ShiftRegionManualCheck COM5
 * </pre>
 */
public final class ShiftRegionManualCheck {

	private static final int PANEL_WIDTH = 400;
	private static final int PANEL_HEIGHT = 300;
	private static final int STRIPE_WIDTH = 20;
	private static final int SHIFT_STEP = 50;

	private ShiftRegionManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: ShiftRegionManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			for (int stripeX : new int[] { 0, 100, 200, 300 }) {
				System.out.println("-> DRAW_RECT filled black stripe at x=" + stripeX + ", deferred");
				drawStripe(client, stripeX);
			}
			System.out.println("-> sending REFRESH(MODE=0x01) - four vertical stripes should appear");
			refresh(client, 0x01);

			System.out.println("-> SHIFT_REGION: whole panel, LEFT, step=" + SHIFT_STEP + ", fill=WHITE, deferred");
			shiftWholePanel(client);
			System.out.println("-> sending REFRESH(MODE=0x01) - stripes should have moved " + SHIFT_STEP
					+ "px left; the x=0 stripe should be gone, a white strip should appear on the right");
			refresh(client, 0x01);

			System.out.println("OK: check the panel for three remaining stripes, each shifted " + SHIFT_STEP
					+ "px left, with a blank strip on the right edge");
		} finally {
			client.close();
		}
	}

	private static void drawStripe(CommandClient client, int x) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) 0);
		payload.putShort((short) STRIPE_WIDTH);
		payload.putShort((short) PANEL_HEIGHT);
		payload.put((byte) Color.BLACK);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) 1); // FILLED
		payload.put((byte) 1); // LINE_WIDTH (unused, filled)
		payload.put((byte) 0x00); // FLAGS: deferred
		send(client, CommandId.DRAW_RECT, payload.array());
	}

	private static void shiftWholePanel(CommandClient client) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) 0);
		payload.putShort((short) 0);
		payload.putShort((short) PANEL_WIDTH);
		payload.putShort((short) PANEL_HEIGHT);
		payload.put((byte) ShiftDirection.LEFT);
		payload.putShort((short) SHIFT_STEP);
		payload.put((byte) Color.WHITE);
		payload.put((byte) 0x00); // FLAGS: deferred
		send(client, CommandId.SHIFT_REGION, payload.array());
	}

	private static void refresh(CommandClient client, int mode) throws Exception {
		try {
			Frame response = client.send(CommandId.REFRESH, new byte[] { (byte) mode }, 10_000);
			System.out.println("   device replied 0x" + Integer.toHexString(response.getCommandId()));
		} catch (CommandNackException e) {
			System.err.println("FAILED: REFRESH NACK status=0x" + Integer.toHexString(e.getStatus()));
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
