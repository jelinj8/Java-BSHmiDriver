package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;

/**
 * Manual, real-hardware verification of PARTIAL_IMAGE_TRANSFER (doc/PROTOCOL.md
 * §7) and REFRESH (§12.8) - next-steps.md #6: sends two black boxes to opposite
 * corners of the panel as deferred writes (FLAGS.REFRESH_NOW=0 - no visible
 * change from either individually), then a single REFRESH(MODE=0x00) flips the
 * union of both at once, proving the working buffer's dirty-region tracking
 * correctly spans multiple deferred writes. NOT part of the automated
 * {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.PartialImageTransferManualCheck COM5
 * </pre>
 */
public final class PartialImageTransferManualCheck {

	private static final int PANEL_WIDTH = 400;
	private static final int PANEL_HEIGHT = 300;
	private static final int BOX_WIDTH = 80; // multiple of PARTIAL_REFRESH_GRANULARITY_X (8)
	private static final int BOX_HEIGHT = 60; // PARTIAL_REFRESH_GRANULARITY_Y is 1, no constraint

	private PartialImageTransferManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: PartialImageTransferManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			System.out.println(
					"-> sending PARTIAL_IMAGE_TRANSFER (top-left box, deferred - no visible change expected yet)");
			sendBlackBox(client, 0, 0);

			System.out.println(
					"-> sending PARTIAL_IMAGE_TRANSFER (bottom-right box, deferred - still no visible change expected)");
			sendBlackBox(client, PANEL_WIDTH - BOX_WIDTH, PANEL_HEIGHT - BOX_HEIGHT);

			System.out.println("-> sending REFRESH(MODE=0x00) - both boxes should appear together now");
			try {
				Frame response = client.send(CommandId.REFRESH, new byte[] { 0x00 }, 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel for two black boxes, top-left and bottom-right");
			} catch (CommandNackException e) {
				System.err.println("FAILED: REFRESH NACK status=0x" + Integer.toHexString(e.getStatus()));
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	private static void sendBlackBox(CommandClient client, int x, int y) throws Exception {
		byte[] decoded = new byte[(BOX_WIDTH / 8) * BOX_HEIGHT];
		java.util.Arrays.fill(decoded, (byte) 0xFF); // bit=1=BLACK (doc/PROTOCOL.md §6)

		ByteBuffer payload = ByteBuffer.allocate(18 + decoded.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) 0x00); // ENCODING = RAW
		payload.put((byte) 0x00); // FLAGS = 0 (REFRESH_NOW not set - deferred)
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) BOX_WIDTH);
		payload.putShort((short) BOX_HEIGHT);
		payload.putInt(decoded.length); // DECODED_LEN
		payload.putInt(decoded.length); // ENCODED_LEN (RAW: same as decoded)
		payload.put(decoded);

		try {
			Frame response = client.send(CommandId.PARTIAL_IMAGE_TRANSFER, payload.array());
			System.out.println("   ACKed (0x" + Integer.toHexString(response.getCommandId()) + ")");
		} catch (CommandNackException e) {
			System.err.println("FAILED: PARTIAL_IMAGE_TRANSFER at (" + x + "," + y + ") NACK status=0x"
					+ Integer.toHexString(e.getStatus()));
			System.exit(1);
		}
	}
}
