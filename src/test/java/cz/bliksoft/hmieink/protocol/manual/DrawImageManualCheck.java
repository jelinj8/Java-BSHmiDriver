package cz.bliksoft.hmieink.protocol.manual;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.DrawImageFlags;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.image.EpiImageCodec;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.Volume;

/**
 * Manual, real-hardware verification of DRAW_IMAGE (doc/PROTOCOL.md §12.7), the
 * {@code .epi} format ({@code EpiImageCodec}), and the FLAGS.IGNORE_MASK bit
 * ({@code DrawImageFlags}): draws a striped background, then three badge icons
 * uploaded via FILE_UPLOAD and DRAW_IMAGEd side by side - (1) a masked badge
 * with default FLAGS (mask respected: corners transparent, stripes show
 * through), (2) the same masked badge with FLAGS.IGNORE_MASK set (corners
 * forced opaque, stripes fully covered), (3) an unmasked badge (no HAS_MASK at
 * all, confirming that path is unaffected by the IGNORE_MASK change). NOT part
 * of the automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.DrawImageManualCheck COM5
 * </pre>
 */
public final class DrawImageManualCheck {

	private static final int ICON_SIZE = 60;

	private DrawImageManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: DrawImageManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			// Solid BLACK backdrop, not stripes: the badge's own "outside rounded rect"
			// corner
			// pixels encode as WHITE in the color plane (see buildBadgeIcon), so a striped
			// background can coincidentally make "transparent, background shows through"
			// and
			// "opaque, drawing the badge's own white" look identical wherever a corner
			// lands on
			// a white stripe. Black is the one background color guaranteed to contrast with
			// that
			// white corner content, so mask-respected (black corners) vs
			// mask-ignored/unmasked
			// (white corners) is unambiguous regardless of exact pixel alignment.
			System.out.println("-> drawing a solid black backdrop (20,20)-(320,140), deferred");
			drawSolidBlack(client, 20, 20, 300, 120);

			BufferedImage masked = buildBadgeIcon();
			byte[] epiMasked = EpiImageCodec.encode(masked, true);
			byte[] epiUnmasked = EpiImageCodec.encode(masked, false);

			System.out.println(
					"-> (1) masked badge, default FLAGS - corners should stay BLACK (background shows through)");
			upload(client, "/badge_masked.epi", epiMasked);
			drawImage(client, 50, 50, "/badge_masked.epi", 0x00);

			System.out.println("-> (2) masked badge, FLAGS.IGNORE_MASK - corners should turn WHITE (forced opaque)");
			drawImage(client, 130, 50, "/badge_masked.epi", DrawImageFlags.IGNORE_MASK);

			System.out
					.println("-> (3) unmasked badge (no HAS_MASK), default FLAGS - corners WHITE too (always opaque)");
			upload(client, "/badge_unmasked.epi", epiUnmasked);
			drawImage(client, 210, 50, "/badge_unmasked.epi", 0x00);

			System.out.println("-> sending REFRESH(MODE=0x01)");
			try {
				Frame response = client.send(CommandId.REFRESH, new byte[] { 0x01 }, 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel: three badges left-to-right on a solid black backdrop - "
						+ "(1) BLACK corners (transparent, black background shows through, badge reads as "
						+ "rounded), (2) WHITE corners (forced opaque, square-cornered look), "
						+ "(3) WHITE corners too (unmasked path, same square-cornered look as (2))");
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

	private static void upload(CommandClient client, String path, byte[] content) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length + 4 + content.length)
				.order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.INTERNAL);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		payload.putInt(content.length);
		payload.put(content);
		send(client, CommandId.FILE_UPLOAD, payload.array());
	}

	private static void drawImage(CommandClient client, int x, int y, String path, int extraFlags) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(8 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) extraFlags); // FLAGS: deferred (WriteFlags.REFRESH_NOW/FULL unset) + extraFlags
		payload.put((byte) Volume.INTERNAL);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		send(client, CommandId.DRAW_IMAGE, payload.array());
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
