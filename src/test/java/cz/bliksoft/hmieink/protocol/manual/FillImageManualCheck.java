package cz.bliksoft.hmieink.protocol.manual;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.EpiImageCodec;
import cz.bliksoft.hmieink.protocol.FillTileMode;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.Volume;

/**
 * Manual, real-hardware verification of FILL_IMAGE (doc/PROTOCOL.md §12.x): uploads one small,
 * deliberately asymmetric tile (a black square in the top-left corner of an otherwise white 12x12
 * cell) and fills three rectangles with it, one per TILE_MODE - a horizontal strip (a decorative
 * border), a vertical strip, and a full rectangle (a background pattern). Each target size is
 * deliberately NOT an exact multiple of the tile size, so the last tile in each run should appear
 * visibly cropped, not wrapped or skipped, proving the clip-based cropping works. NOT part of the
 * automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.FillImageManualCheck COM5
 * </pre>
 */
public final class FillImageManualCheck {

	private static final String TILE_PATH = "/fill_tile.epi";
	private static final int TILE_SIZE = 12;

	private FillImageManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: FillImageManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			byte[] tile = EpiImageCodec.encode(buildTile());
			System.out.println("-> FILE_UPLOAD " + TILE_PATH + " (" + TILE_SIZE + "x" + TILE_SIZE + ", " + tile.length
					+ " bytes)");
			upload(client, TILE_PATH, tile);

			System.out.println("-> FILL_IMAGE HORIZONTAL at (20,20), WIDTH=100 (not a multiple of "
					+ TILE_SIZE + " - last tile should be cropped)");
			fill(client, 20, 20, 100, 0, FillTileMode.HORIZONTAL);

			System.out.println("-> FILL_IMAGE VERTICAL at (20,50), HEIGHT=80 (not a multiple of " + TILE_SIZE
					+ " - last tile should be cropped)");
			fill(client, 20, 50, 0, 80, FillTileMode.VERTICAL);

			System.out.println("-> FILL_IMAGE BOTH at (250,20), 100x100 (not a multiple of " + TILE_SIZE
					+ " in either axis - right/bottom edges should be cropped)");
			fill(client, 250, 20, 100, 100, FillTileMode.BOTH);

			System.out.println("-> sending REFRESH(MODE=0x01)");
			try {
				Frame response = client.send(CommandId.REFRESH, new byte[] { 0x01 }, 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel: a horizontal row of small corner-squares (cropped at the "
						+ "right end), a vertical column of the same (cropped at the bottom), and a "
						+ "10x10-ish grid of them filling a square block (cropped at the right and bottom "
						+ "edges) - all using the same repeating asymmetric tile");
			} catch (CommandNackException e) {
				System.err.println("FAILED: REFRESH NACK status=0x" + Integer.toHexString(e.getStatus()));
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	/** A 12x12 white cell with a 6x6 black square in the top-left corner - deliberately asymmetric. */
	private static BufferedImage buildTile() {
		BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < TILE_SIZE; y++) {
			for (int x = 0; x < TILE_SIZE; x++) {
				boolean black = x < TILE_SIZE / 2 && y < TILE_SIZE / 2;
				image.setRGB(x, y, black ? 0xFF000000 : 0xFFFFFFFF);
			}
		}
		return image;
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

	private static void fill(CommandClient client, int x, int y, int width, int height, int tileMode)
			throws Exception {
		byte[] pathBytes = TILE_PATH.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(13 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) width);
		payload.putShort((short) height);
		payload.put((byte) tileMode);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) 0x00); // FLAGS: deferred
		payload.put((byte) Volume.INTERNAL);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		send(client, CommandId.FILL_IMAGE, payload.array());
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
