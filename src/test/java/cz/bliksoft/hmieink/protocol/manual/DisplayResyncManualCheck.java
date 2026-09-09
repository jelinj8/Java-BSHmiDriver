package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.PowerMode;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.WakeReason;

/**
 * Manual, real-hardware, VISUAL verification of {@code WorkingBuffer::ensureControllerReady()}
 * (doc/PROTOCOL.md §17 design note 79/80) - specifically the ghosting risk a bare re-init (without
 * reseeding the SSD1683's own "current"/"previous" RAM banks from {@code panelBuffer_}) would leave
 * behind: drawing a small white rect *inside* a region that already had real (non-blank) content,
 * immediately after a {@code LOW_POWER} wake. Unlike the other manual checks this asserts nothing
 * programmatically - there is no way to read the controller's own internal RAM banks over the wire,
 * only this class's own (potentially wrong, if the bug being tested for is present)
 * {@code panelBuffer_} shadow - so this just sets up the scenario and prints what to look for.
 * NOT part of the automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.DisplayResyncManualCheck COM5
 * </pre>
 */
public final class DisplayResyncManualCheck {

	private DisplayResyncManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: DisplayResyncManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		SerialFrameTransport transport = new SerialFrameTransport(portDescriptor);
		CommandClient client = new CommandClient(transport);
		System.out.println("Connecting to " + portDescriptor + "...");
		client.connect();
		try {
			System.out.println("-> DRAW_RECT filled black (100,100,120,120), REFRESH_NOW - establishing real "
					+ "(non-blank) content");
			drawRect(client, 100, 100, 120, 120, Color.BLACK, true);

			System.out.println("-> SET_POWER_MODE LOW_POWER, WAKE_AFTER_MS=3000 - display power will be cut, "
					+ "then lazily restored on the very next draw below");
			client.send(CommandId.SET_POWER_MODE, buildSetPowerModePayload(PowerMode.LOW_POWER, 3000));
			Thread.sleep(4500);
			int wakeReason = readPowerStatusWakeReason(client);
			System.out.println("   woke via reason 0x" + Integer.toHexString(wakeReason)
					+ (wakeReason == WakeReason.LOW_POWER_TIMER ? " (LOW_POWER_TIMER, as expected)" : " (UNEXPECTED)"));

			System.out.println("-> immediately after wake: DRAW_RECT filled white (130,130,50,50), REFRESH_NOW "
					+ "- inset INSIDE the still-black region above, exactly the ghosting-risk scenario "
					+ "(controller RAM was just reset by the lazy reinit this draw triggers)");
			drawRect(client, 130, 130, 50, 50, Color.WHITE, true);

			System.out.println();
			System.out.println("Look at the panel now: expect a clean black square (100,100)-(220,220) with a "
					+ "sharp white square (130,130)-(180,180) cut cleanly out of its middle - no ghosting, no "
					+ "stray dark/light pixels, no visible artifact anywhere in or around the white square.");
		} finally {
			client.close();
		}
	}

	private static void drawRect(CommandClient client, int x, int y, int w, int h, int color, boolean filled)
			throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) w);
		payload.putShort((short) h);
		payload.put((byte) color);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) (filled ? 1 : 0));
		payload.put((byte) 1);	  // LINE_WIDTH
		payload.put((byte) 0x01);	// FLAGS: REFRESH_NOW, partial
		client.send(CommandId.DRAW_RECT, payload.array());
	}

	private static int readPowerStatusWakeReason(CommandClient client) throws Exception {
		return client.send(CommandId.POWER_STATUS_REQUEST, new byte[0]).getPayload()[1] & 0xFF;
	}

	private static byte[] buildSetPowerModePayload(int mode, long wakeAfterMs) {
		return new byte[] { (byte) mode, 0, (byte) (wakeAfterMs & 0xFF), (byte) ((wakeAfterMs >> 8) & 0xFF),
				(byte) ((wakeAfterMs >> 16) & 0xFF), (byte) ((wakeAfterMs >> 24) & 0xFF), 0 };
	}
}
