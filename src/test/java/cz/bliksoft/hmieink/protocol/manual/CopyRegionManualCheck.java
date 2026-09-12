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

/**
 * Manual, real-hardware verification of COPY_REGION (doc/PROTOCOL.md §12.11):
 * draws one filled circle, then copies its bounding box to two other locations
 * without redrawing it - proving content can be duplicated ("copy + paste")
 * rather than resent from the PC. NOT part of the automated {@code mvn test}
 * suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.CopyRegionManualCheck COM5
 * </pre>
 */
public final class CopyRegionManualCheck {

	private CopyRegionManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: CopyRegionManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			System.out.println("-> DRAW_CIRCLE filled, center (60,60) r=25, deferred");
			drawFilledCircle(client, 60, 60, 25);

			System.out.println("-> COPY_REGION: (30,30,60,60) -> (150,30), deferred");
			copyRegion(client, 30, 30, 150, 30, 60, 60);

			System.out.println("-> COPY_REGION: (30,30,60,60) -> (250,30), deferred");
			copyRegion(client, 30, 30, 250, 30, 60, 60);

			System.out.println("-> sending REFRESH(MODE=0x01) - three identical circles should appear in a row");
			try {
				Frame response = client.send(CommandId.REFRESH, new byte[] { 0x01 }, 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel for three identical filled circles");
			} catch (CommandNackException e) {
				System.err.println("FAILED: REFRESH NACK status=0x" + Integer.toHexString(e.getStatus()));
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	private static void drawFilledCircle(CommandClient client, int centerX, int centerY, int radius) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) centerX);
		payload.putShort((short) centerY);
		payload.putShort((short) radius);
		payload.put((byte) Color.BLACK);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) 1); // FILLED
		payload.put((byte) 1); // LINE_WIDTH (unused, filled)
		payload.put((byte) 0x00); // FLAGS: deferred
		send(client, CommandId.DRAW_CIRCLE, payload.array());
	}

	private static void copyRegion(CommandClient client, int srcX, int srcY, int dstX, int dstY, int w, int h)
			throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) srcX);
		payload.putShort((short) srcY);
		payload.putShort((short) dstX);
		payload.putShort((short) dstY);
		payload.putShort((short) w);
		payload.putShort((short) h);
		payload.put((byte) 0x00); // FLAGS: deferred
		send(client, CommandId.COPY_REGION, payload.array());
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
