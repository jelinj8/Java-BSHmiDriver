package cz.bliksoft.hmieink.protocol.manual;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.EpiImageCodec;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.ImageRowAlign;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.Volume;

/**
 * Manual, real-hardware verification of DRAW_IMAGE_ROW (doc/PROTOCOL.md §12.x): uploads three
 * differently-sized icon tiles (each a black square inset with a white margin, so adjacent tiles
 * stay visually distinct even at SPACING=0), then draws the same three-icon row four times, once
 * per ALIGN value (LEFT/CENTER/RIGHT/BLOCK), each inside a visible outlined reference box (drawn
 * via DRAW_RECT) so the alignment is checkable directly against the box edges. NOT part of the
 * automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.DrawImageRowManualCheck COM5
 * </pre>
 */
public final class DrawImageRowManualCheck {

	private static final int BOX_X = 60;
	private static final int BOX_WIDTH = 250;
	private static final int ICON_HEIGHT = 30;
	private static final String[] ICON_PATHS = { "/row_icon_a.epi", "/row_icon_b.epi", "/row_icon_c.epi" };
	private static final int[] ICON_WIDTHS = { 15, 25, 20 };

	private DrawImageRowManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: DrawImageRowManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			for (int i = 0; i < ICON_PATHS.length; i++) {
				byte[] epi = EpiImageCodec.encode(buildIconTile(ICON_WIDTHS[i], ICON_HEIGHT));
				System.out.println("-> FILE_UPLOAD " + ICON_PATHS[i] + " (" + ICON_WIDTHS[i] + "x" + ICON_HEIGHT
						+ ", " + epi.length + " bytes)");
				upload(client, ICON_PATHS[i], epi);
			}

			int y = 30;
			drawRow(client, y, ImageRowAlign.LEFT, "LEFT", 5);
			y += 60;
			drawRow(client, y, ImageRowAlign.CENTER, "CENTER", 5);
			y += 60;
			drawRow(client, y, ImageRowAlign.RIGHT, "RIGHT", 5);
			y += 60;
			drawRow(client, y, ImageRowAlign.BLOCK, "BLOCK", 0);

			System.out.println("-> sending REFRESH(MODE=0x01)");
			try {
				Frame response = client.send(CommandId.REFRESH, new byte[] { 0x01 }, 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel: four outlined boxes, each containing the same three icon "
						+ "tiles (widths 15/25/20) - LEFT packed against the box's left edge, CENTER "
						+ "centered in the box, RIGHT packed against the box's right edge, and BLOCK "
						+ "with the first tile flush left, the last tile flush right, and the leftover "
						+ "space evenly split between the two gaps");
			} catch (CommandNackException e) {
				System.err.println("FAILED: REFRESH NACK status=0x" + Integer.toHexString(e.getStatus()));
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	private static void drawRow(CommandClient client, int y, int align, String label, int spacing) throws Exception {
		System.out.println("-> " + label + ": DRAW_RECT outline box (60," + y + ",250,30), then DRAW_IMAGE_ROW");
		drawRectOutline(client, BOX_X, y, BOX_WIDTH, ICON_HEIGHT);
		drawImageRow(client, BOX_X, y, BOX_WIDTH, align, spacing, Arrays.asList(ICON_PATHS));
	}

	private static BufferedImage buildIconTile(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		int margin = 3;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean black = x >= margin && x < width - margin && y >= margin && y < height - margin;
				image.setRGB(x, y, black ? 0xFF000000 : 0xFFFFFFFF);
			}
		}
		return image;
	}

	private static void drawRectOutline(CommandClient client, int x, int y, int w, int h) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) w);
		payload.putShort((short) h);
		payload.put((byte) Color.BLACK);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) 0); // FILLED = false (outline)
		payload.put((byte) 1); // LINE_WIDTH
		payload.put((byte) 0x00); // FLAGS: deferred
		send(client, CommandId.DRAW_RECT, payload.array());
	}

	private static void drawImageRow(CommandClient client, int x, int y, int width, int align, int spacing,
			List<String> paths) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteBuffer header = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
		header.putShort((short) x);
		header.putShort((short) y);
		header.putShort((short) width);
		header.put((byte) align);
		header.putShort((short) spacing);
		header.put((byte) DrawMode.REPLACE);
		header.put((byte) 0x00); // FLAGS: deferred
		header.put((byte) Volume.INTERNAL);
		header.put((byte) paths.size());
		out.write(header.array(), 0, header.array().length);
		for (String path : paths) {
			byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
			out.write(pathBytes.length);
			out.write(pathBytes, 0, pathBytes.length);
		}
		send(client, CommandId.DRAW_IMAGE_ROW, out.toByteArray());
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
