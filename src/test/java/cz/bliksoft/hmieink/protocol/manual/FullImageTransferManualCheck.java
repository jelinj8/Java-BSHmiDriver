package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.RlePackBits;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.WriteFlags;

/**
 * Manual, real-hardware verification of FULL_IMAGE_TRANSFER (doc/PROTOCOL.md
 * §6, next-steps.md #4): sends a real 400x300 test pattern (horizontal
 * black/white stripes, RLE-encoded) over Serial via {@link CommandClient} and
 * checks the device replies ACK. Does NOT verify pixels actually appeared
 * correctly - that still needs a human looking at the panel, same as
 * {@code displaySelfTest()}'s bring-up check. NOT part of the automated
 * {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.FullImageTransferManualCheck COM5
 * </pre>
 */
public final class FullImageTransferManualCheck {

	// Hardcoded to this board's panel (doc/PROTOCOL.md §6 has no width/height
	// fields - it always
	// targets the full panel at the handshake-reported resolution; a real client
	// would read this
	// from HANDSHAKE_RESPONSE's DISPLAY_WIDTH_PX/HEIGHT_PX TLVs instead of
	// hardcoding it here).
	private static final int WIDTH = 400;
	private static final int HEIGHT = 300;
	private static final int STRIPE_HEIGHT = 20;

	private FullImageTransferManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: FullImageTransferManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println(
				"Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE + " baud...");
		client.connect();
		try {
			byte[] decoded = buildStripePattern();
			byte[] encoded = RlePackBits.encode(decoded);
			System.out.println("Test pattern: " + decoded.length + " bytes decoded, " + encoded.length
					+ " bytes RLE-encoded (" + STRIPE_HEIGHT + "px horizontal stripes)");

			ByteBuffer payload = ByteBuffer.allocate(10 + encoded.length).order(ByteOrder.LITTLE_ENDIAN);
			payload.put((byte) 0x01); // ENCODING = RLE_PACKBITS
			payload.put((byte) (WriteFlags.REFRESH_NOW | WriteFlags.REFRESH_FULL)); // FLAGS
			payload.putInt(decoded.length); // DECODED_LEN
			payload.putInt(encoded.length); // ENCODED_LEN
			payload.put(encoded);

			System.out.println("-> sending FULL_IMAGE_TRANSFER (this triggers a full-panel refresh, ~1-2s)");
			try {
				Frame response = client.send(CommandId.FULL_IMAGE_TRANSFER, payload.array(), 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel for horizontal stripes");
			} catch (CommandNackException e) {
				System.err.println("FAILED: NACK status=0x" + Integer.toHexString(e.getStatus()));
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	// Wire polarity (doc/PROTOCOL.md §6): bit=1=BLACK, bit=0=WHITE, row-major
	// top-to-bottom,
	// ceil(WIDTH/8) bytes/row, MSB-first.
	private static byte[] buildStripePattern() {
		int bytesPerRow = (WIDTH + 7) / 8;
		byte[] bitmap = new byte[bytesPerRow * HEIGHT];
		for (int y = 0; y < HEIGHT; y++) {
			boolean black = (y / STRIPE_HEIGHT) % 2 == 0;
			if (black) {
				int rowStart = y * bytesPerRow;
				java.util.Arrays.fill(bitmap, rowStart, rowStart + bytesPerRow, (byte) 0xFF);
			}
		}
		return bitmap;
	}
}
