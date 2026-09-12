package cz.bliksoft.hmieink.protocol.manual;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.DrawImageFlags;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.EpiImageCodec;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;

/**
 * Manual, real-hardware verification of DRAW_IMAGE_DATA (doc/PROTOCOL.md
 * §12.17, {@code CommandId.DRAW_IMAGE_DATA = 0x0310}) - hand-builds the payload
 * directly (bypassing {@code CommandSchema}/{@code HmiDevice}, same style as
 * {@code FullImageTransferManualCheck}) so it can be run against real hardware
 * independent of the Java client's own schema/typed-API support for the
 * command. Draws the same three-badge scenario as {@link DrawImageManualCheck}
 * - masked/default, masked/IGNORE_MASK, unmasked - but in one command each,
 * with no FILE_UPLOAD step at all. Requires firmware advertising
 * {@code FEATURE_BITMASK} bit9; older firmware NACKs with UNSUPPORTED_COMMAND.
 * NOT part of the automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.DrawImageDataManualCheck COM5
 * </pre>
 */
public final class DrawImageDataManualCheck {

	private static final int ICON_SIZE = 60;

	private DrawImageDataManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: DrawImageDataManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			// Solid BLACK backdrop, not stripes - see DrawImageManualCheck's identical
			// comment:
			// the badge's "outside rounded rect" corner pixels encode as WHITE in the color
			// plane,
			// so black is the one background guaranteed to contrast with them regardless of
			// exact
			// pixel alignment.
			System.out.println("-> drawing a solid black backdrop (20,20)-(320,140), deferred");
			drawSolidBlack(client, 20, 20, 300, 120);

			BufferedImage masked = buildBadgeIcon();
			byte[] epiMasked = EpiImageCodec.encode(masked, true);
			byte[] epiUnmasked = EpiImageCodec.encode(masked, false);
			System.out.println(
					"   encoded: masked=" + epiMasked.length + " bytes, unmasked=" + epiUnmasked.length + " bytes");

			System.out.println("-> (1) DRAW_IMAGE_DATA masked badge, default FLAGS - corners should stay BLACK");
			drawImageData(client, 50, 50, epiMasked, 0x00);

			System.out.println("-> (2) DRAW_IMAGE_DATA masked badge, FLAGS.IGNORE_MASK - corners should turn WHITE");
			drawImageData(client, 130, 50, epiMasked, DrawImageFlags.IGNORE_MASK);

			System.out
					.println("-> (3) DRAW_IMAGE_DATA unmasked badge (no HAS_MASK), default FLAGS - corners WHITE too");
			drawImageData(client, 210, 50, epiUnmasked, 0x00);

			System.out.println("-> sending REFRESH(MODE=0x01)");
			try {
				Frame response = client.send(CommandId.REFRESH, new byte[] { 0x01 }, 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel: three badges left-to-right on a solid black backdrop - "
						+ "(1) BLACK corners (transparent, rounded look), (2) WHITE corners (forced "
						+ "opaque, square look), (3) WHITE corners too (unmasked path) - identical result "
						+ "to DrawImageManualCheck, but with no FILE_UPLOAD ever sent");
			} catch (CommandNackException e) {
				System.err.println("FAILED: REFRESH NACK status=0x" + Integer.toHexString(e.getStatus()));
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	private static BufferedImage buildBadgeIcon() {
		int cornerRadius = 10;
		int circleRadius = 20;
		int center = ICON_SIZE / 2;
		BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < ICON_SIZE; y++) {
			for (int x = 0; x < ICON_SIZE; x++) {
				if (!isInsideRoundedRect(x, y, ICON_SIZE, ICON_SIZE, cornerRadius)) {
					image.setRGB(x, y, 0x00FFFFFF);
					continue;
				}
				double dx = x - center + 0.5;
				double dy = y - center + 0.5;
				boolean insideCircle = dx * dx + dy * dy <= (double) circleRadius * circleRadius;
				image.setRGB(x, y, insideCircle ? 0xFF000000 : 0xFFFFFFFF);
			}
		}
		return image;
	}

	private static boolean isInsideRoundedRect(int x, int y, int w, int h, int r) {
		int cx = clamp(x, r, w - 1 - r);
		int cy = clamp(y, r, h - 1 - r);
		double dx = x - cx;
		double dy = y - cy;
		return dx * dx + dy * dy <= (double) r * r;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(v, hi));
	}

	private static void drawSolidBlack(CommandClient client, int x, int y, int w, int h) throws Exception {
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

	// doc/PROTOCOL.md §12.17: X(u16LE) Y(u16LE) DRAW_MODE(u8) FLAGS(u8)
	// DATA_LEN(u32LE) DATA(.epi bytes)
	private static void drawImageData(CommandClient client, int x, int y, byte[] epiBytes, int extraFlags)
			throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(10 + epiBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) extraFlags); // FLAGS: deferred (WriteFlags.REFRESH_NOW/FULL unset) + extraFlags
		payload.putInt(epiBytes.length);
		payload.put(epiBytes);
		send(client, CommandId.DRAW_IMAGE_DATA, payload.array());
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
