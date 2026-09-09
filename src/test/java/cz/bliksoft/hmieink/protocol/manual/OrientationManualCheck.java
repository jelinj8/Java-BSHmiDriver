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
import cz.bliksoft.hmieink.protocol.OrientationFlags;
import cz.bliksoft.hmieink.protocol.Rotation;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.TextAlign;
import cz.bliksoft.hmieink.protocol.TextBackground;

/**
 * Manual, real-hardware verification of SET_ORIENTATION (doc/PROTOCOL.md §12.12): draws a short
 * text label at the exact same small logical anchor (10,10) under each of the four ROTATION
 * values in turn. Because rotation maps a fixed logical point to a different physical corner each
 * time (see WorkingBuffer::toPhysical()'s formulas), the four labels should appear near four
 * different physical corners without any manual placement math, and each should itself be visibly
 * rotated in place (every glyph pixel goes through the same logical-&gt;physical mapping, not just
 * the anchor). Then exercises MIRROR_H/MIRROR_V (at ROTATE_0) with three more labels at distinct
 * anchors. Resets to ROTATE_0/no mirror at the end. NOT part of the automated {@code mvn test}
 * suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.OrientationManualCheck COM5
 * </pre>
 */
public final class OrientationManualCheck {

	private OrientationManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: OrientationManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			System.out.println("-> ROTATE_0: DRAW_TEXT \"R0\" at logical (10,10)");
			setOrientation(client, Rotation.ROTATE_0, false, false);
			drawText(client, 10, 10, "R0");

			System.out.println("-> ROTATE_90: DRAW_TEXT \"R90\" at the same logical (10,10)");
			setOrientation(client, Rotation.ROTATE_90, false, false);
			drawText(client, 10, 10, "R90");

			System.out.println("-> ROTATE_180: DRAW_TEXT \"R180\" at the same logical (10,10)");
			setOrientation(client, Rotation.ROTATE_180, false, false);
			drawText(client, 10, 10, "R180");

			System.out.println("-> ROTATE_270: DRAW_TEXT \"R270\" at the same logical (10,10)");
			setOrientation(client, Rotation.ROTATE_270, false, false);
			drawText(client, 10, 10, "R270");

			System.out.println("-> back to ROTATE_0, no mirror: DRAW_TEXT \"NORMAL\" at (10,140)");
			setOrientation(client, Rotation.ROTATE_0, false, false);
			drawText(client, 10, 140, "NORMAL");

			System.out.println("-> MIRROR_H (ROTATE_0): DRAW_TEXT \"MIRRORH\" at (10,180)");
			setOrientation(client, Rotation.ROTATE_0, true, false);
			drawText(client, 10, 180, "MIRRORH");

			System.out.println("-> MIRROR_V (ROTATE_0): DRAW_TEXT \"MIRRORV\" at (10,220)");
			setOrientation(client, Rotation.ROTATE_0, false, true);
			drawText(client, 10, 220, "MIRRORV");

			System.out.println("-> resetting to ROTATE_0, no mirror");
			setOrientation(client, Rotation.ROTATE_0, false, false);

			System.out.println("-> sending REFRESH(MODE=0x01)");
			try {
				Frame response = client.send(CommandId.REFRESH, new byte[] { 0x01 }, 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel for where each of the seven labels landed and how it reads");
			} catch (CommandNackException e) {
				System.err.println("FAILED: REFRESH NACK status=0x" + Integer.toHexString(e.getStatus()));
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	private static void setOrientation(CommandClient client, int rotation, boolean mirrorH, boolean mirrorV)
			throws Exception {
		int flags = (mirrorH ? OrientationFlags.MIRROR_H : 0) | (mirrorV ? OrientationFlags.MIRROR_V : 0);
		ByteBuffer payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) rotation);
		payload.put((byte) flags);
		send(client, CommandId.SET_ORIENTATION, payload.array());
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
