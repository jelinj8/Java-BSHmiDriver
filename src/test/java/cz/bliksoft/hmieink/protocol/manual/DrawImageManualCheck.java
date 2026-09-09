package cz.bliksoft.hmieink.protocol.manual;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.EpiImageCodec;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.Volume;

/**
 * Manual, real-hardware verification of DRAW_IMAGE (doc/PROTOCOL.md §12.7) and the {@code .epi}
 * format ({@code EpiImageCodec}): draws a striped background, encodes a small rounded-square
 * "badge" icon (a filled black circle on a white rounded-rect, with fully transparent corners
 * outside the rounding) as a {@code .epi} file with a transparency mask, uploads it to
 * VOLUME=INTERNAL via FILE_UPLOAD, then DRAW_IMAGEs it on top of the stripes - the badge's four
 * corners should show the stripes showing through (mask working, not just "happens to look white
 * already"), while the rest of the badge fully replaces the stripes underneath it. NOT part of the
 * automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.DrawImageManualCheck COM5
 * </pre>
 */
public final class DrawImageManualCheck {

	private static final String ICON_PATH = "/badge_test.epi";
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
			System.out.println("-> drawing a striped background (20,20)-(140,140), deferred");
			drawStripes(client, 20, 20, 120, 120);

			System.out.println("-> encoding a " + ICON_SIZE + "x" + ICON_SIZE + " rounded-badge icon with a "
					+ "transparency mask (.epi, with mask)");
			byte[] epi = EpiImageCodec.encode(buildBadgeIcon(), true);
			System.out.println("   encoded: " + epi.length + " bytes");

			System.out.println("-> FILE_UPLOAD " + ICON_PATH + " to VOLUME=INTERNAL");
			upload(client, ICON_PATH, epi);

			System.out.println("-> DRAW_IMAGE " + ICON_PATH + " at (50,50), REPLACE, deferred");
			drawImage(client, 50, 50, ICON_PATH);

			System.out.println("-> sending REFRESH(MODE=0x01)");
			try {
				Frame response = client.send(CommandId.REFRESH, new byte[] { 0x01 }, 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel: a striped background with a white rounded-badge icon "
						+ "(black filled circle inside) drawn on top - the badge's four corners should "
						+ "show the stripes showing through (transparent), not solid white");
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

	private static void drawStripes(CommandClient client, int x, int y, int w, int h) throws Exception {
		int stripeWidth = 10;
		for (int sx = 0; sx < w; sx += stripeWidth * 2) {
			drawRect(client, x + sx, y, Math.min(stripeWidth, w - sx), h);
		}
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

	private static void upload(CommandClient client, String path, byte[] content) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload =
				ByteBuffer.allocate(2 + pathBytes.length + 4 + content.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.INTERNAL);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		payload.putInt(content.length);
		payload.put(content);
		send(client, CommandId.FILE_UPLOAD, payload.array());
	}

	private static void drawImage(CommandClient client, int x, int y, String path) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(8 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) 0x00); // FLAGS: deferred
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
