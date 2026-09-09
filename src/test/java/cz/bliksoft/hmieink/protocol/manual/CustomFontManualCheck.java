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
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.Status;
import cz.bliksoft.hmieink.protocol.TextAlign;
import cz.bliksoft.hmieink.protocol.TextBackground;
import cz.bliksoft.hmieink.protocol.Volume;
import cz.bliksoft.hmieink.protocol.WriteFlags;

/**
 * Manual, real-hardware verification of the custom folder-driven proportional font -
 * {@code DRAW_TEXT FONT_ID=0xFF} and {@code SET_CUSTOM_FONT_FOLDER} (doc/PROTOCOL.md
 * §12.6.1/§12.6.2). Hand-builds three small {@code .gly} glyph files (a 5x7 'A', a shorter 4x5 'B'
 * - deliberately shorter than 'A' to make bottom-alignment visible - and a 6x7 box-shaped {@code
 * XX} fallback/line-height reference), uploads them via ordinary {@code FILE_UPLOAD}, then
 * exercises: the NACK(FILE_NOT_FOUND) pre-flight before any folder is ever configured; direct
 * ({@code S:}/{@code F:}) and once-resolved PSRAM-indirected ({@code R:}) folder selection;
 * proportional-width wrap/alignment/zero-spacing "welding" and the {@code XX} fallback for an
 * unmapped byte, drawn on the real panel for visual confirmation; and the remaining NACK edge
 * cases ({@code BAD_PARAMETERS} for a malformed/missing prefix, {@code FILE_NOT_FOUND} for a
 * nonexistent {@code R:} pointer file, a nonexistent folder, and a folder missing its own {@code
 * XX.gly}). Prefers {@code VOLUME=SD} when a card is present, falling back to {@code
 * VOLUME=INTERNAL} (flash) otherwise - both are valid custom-font targets (PSRAM is not, since it
 * has no subdirectories). NOT part of the automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.CustomFontManualCheck COM5
 * </pre>
 */
public final class CustomFontManualCheck {

	private static int failures = 0;

	private CustomFontManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: CustomFontManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test)...");
		client.connect();
		try {
			boolean sdPresent = isSdPresent(client);
			int volume = sdPresent ? Volume.SD : Volume.INTERNAL;
			char volumePrefix = sdPresent ? 'S' : 'F';
			System.out.println("-> STORAGE_INFO_REQUEST VOLUME=SD - PRESENT=" + sdPresent + " (using "
					+ (sdPresent ? "SD" : "INTERNAL/flash") + " for this check)");

			System.out.println("-> DRAW_TEXT FONT_ID=0xFF before any SET_CUSTOM_FONT_FOLDER (fresh boot) - "
					+ "expect NACK(FILE_NOT_FOUND)");
			expectNack(client, CommandId.DRAW_TEXT, drawCustomPayload(10, 10, 0, TextAlign.LEFT, false, new byte[] { 0x41 }),
					Status.FILE_NOT_FOUND);

			System.out.println("-> FILE_UPLOAD glyph set to /customfont/ on " + (sdPresent ? "SD" : "INTERNAL"));
			upload(client, volume, "/customfont/41.gly", glyphFromArt(".###.", "#...#", "#...#", "#####", "#...#",
					"#...#", "#...#"));
			upload(client, volume, "/customfont/42.gly", glyphFromArt("###.", "#..#", "###.", "#..#", "###."));
			upload(client, volume, "/customfont/XX.gly", glyphFromArt("######", "#....#", "#....#", "#....#",
					"#....#", "#....#", "######"));

			System.out.println("-> SET_CUSTOM_FONT_FOLDER " + volumePrefix + ":/customfont (direct path)");
			setCustomFontFolder(client, volumePrefix + ":/customfont");

			int y = 20;
			System.out.println("-> DRAW_TEXT FONT_ID=0xFF \"AB\" + unmapped 0x43 (falls back to XX), LEFT, at y=" + y);
			send(client, CommandId.DRAW_TEXT, drawCustomPayload(10, y, 0, TextAlign.LEFT, false, new byte[] { 0x41, 0x42, 0x43 }));
			y += 20;
			System.out.println("-> DRAW_TEXT FONT_ID=0xFF \"ABAB\" welded (no extra spacing), CENTER, WIDTH=200, at y="
					+ y);
			send(client, CommandId.DRAW_TEXT,
					drawCustomPayload(10, y, 200, TextAlign.CENTER, false, new byte[] { 0x41, 0x42, 0x41, 0x42 }));
			y += 20;
			System.out.println("-> DRAW_TEXT FONT_ID=0xFF \"A B A B A B\", WRAP, WIDTH=40, RIGHT, at y=" + y
					+ " (proportional wrap - check line breaks don't assume equal glyph widths)");
			send(client, CommandId.DRAW_TEXT, drawCustomPayload(10, y, 40, TextAlign.RIGHT, true,
					new byte[] { 0x41, 0x20, 0x42, 0x20, 0x41, 0x20, 0x42, 0x20, 0x41, 0x20, 0x42 }));
			y += 60;

			System.out.println("-> FILE_UPLOAD /font_ptr.txt = \"" + volumePrefix + ":/customfont\" to VOLUME=PSRAM");
			upload(client, Volume.PSRAM, "/font_ptr.txt", (volumePrefix + ":/customfont").getBytes(StandardCharsets.UTF_8));
			System.out.println("-> SET_CUSTOM_FONT_FOLDER R:/font_ptr.txt (once-resolved PSRAM indirection)");
			setCustomFontFolder(client, "R:/font_ptr.txt");
			System.out.println("-> DRAW_TEXT FONT_ID=0xFF \"AB\" again at y=" + y
					+ " (should look identical to the direct-path draw above)");
			send(client, CommandId.DRAW_TEXT, drawCustomPayload(10, y, 0, TextAlign.LEFT, false, new byte[] { 0x41, 0x42 }));

			System.out.println("-> sending REFRESH(MODE=0x01)");
			try {
				Frame response = client.send(CommandId.REFRESH, new byte[] { 0x01 }, 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel: proportional widths, 'B' bottom-aligned against 'A'/XX's taller "
						+ "line height, welded 'ABAB', a wrapped A/B sequence, and the two \"AB\" lines matching");
			} catch (CommandNackException e) {
				System.err.println("FAILED: REFRESH NACK status=0x" + Integer.toHexString(e.getStatus()));
				failures++;
			}

			System.out.println();
			System.out.println("-- NACK edge cases --");
			System.out.println("-> SET_CUSTOM_FONT_FOLDER with no prefix at all - expect NACK(BAD_PARAMETERS)");
			expectNack(client, CommandId.SET_CUSTOM_FONT_FOLDER, setCustomFontFolderPayload("nocolon"),
					Status.BAD_PARAMETERS);
			System.out.println("-> SET_CUSTOM_FONT_FOLDER with an unrecognized prefix - expect NACK(BAD_PARAMETERS)");
			expectNack(client, CommandId.SET_CUSTOM_FONT_FOLDER, setCustomFontFolderPayload("X:/customfont"),
					Status.BAD_PARAMETERS);
			System.out.println("-> SET_CUSTOM_FONT_FOLDER R: pointing at a nonexistent PSRAM file - expect "
					+ "NACK(FILE_NOT_FOUND)");
			expectNack(client, CommandId.SET_CUSTOM_FONT_FOLDER, setCustomFontFolderPayload("R:/no_such_pointer.txt"),
					Status.FILE_NOT_FOUND);
			System.out.println("-> SET_CUSTOM_FONT_FOLDER pointing at a nonexistent folder - expect "
					+ "NACK(FILE_NOT_FOUND)");
			expectNack(client, CommandId.SET_CUSTOM_FONT_FOLDER,
					setCustomFontFolderPayload(volumePrefix + ":/no_such_folder_xyz"), Status.FILE_NOT_FOUND);

			System.out.println("-> FILE_UPLOAD a folder with only 41.gly, no XX.gly, then SET_CUSTOM_FONT_FOLDER "
					+ "it, then DRAW_TEXT FONT_ID=0xFF - expect NACK(FILE_NOT_FOUND) (no valid line-height "
					+ "reference)");
			upload(client, volume, "/customfont_noxx/41.gly", glyphFromArt(".###.", "#...#", "#...#", "#####",
					"#...#", "#...#", "#...#"));
			setCustomFontFolder(client, volumePrefix + ":/customfont_noxx");
			expectNack(client, CommandId.DRAW_TEXT, drawCustomPayload(10, 10, 0, TextAlign.LEFT, false, new byte[] { 0x41 }),
					Status.FILE_NOT_FOUND);

			System.out.println();
			if (failures == 0) {
				System.out.println("ALL PROGRAMMATIC CHECKS PASSED - now visually confirm the panel per the notes "
						+ "printed above");
			} else {
				System.out.println("FAILURES: " + failures);
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	// Packs a glyph from '#'/'.' ASCII art rows into a .gly file (doc/PROTOCOL.md §12.6.1): "GLY1" +
	// FORMAT_VERSION(1) + WIDTH(u16LE) + HEIGHT(u16LE) + packed 1bpp bitmap, row-major, MSB-first,
	// bit=1=ink. All rows must be the same length.
	private static byte[] glyphFromArt(String... rows) {
		int height = rows.length;
		int width = rows[0].length();
		int bytesPerRow = (width + 7) / 8;
		byte[] bitmap = new byte[bytesPerRow * height];
		for (int y = 0; y < height; y++) {
			String row = rows[y];
			for (int x = 0; x < width; x++) {
				if (row.charAt(x) == '#') {
					bitmap[y * bytesPerRow + x / 8] |= (byte) (0x80 >> (x % 8));
				}
			}
		}
		ByteBuffer buf = ByteBuffer.allocate(9 + bitmap.length).order(ByteOrder.LITTLE_ENDIAN);
		buf.put((byte) 'G');
		buf.put((byte) 'L');
		buf.put((byte) 'Y');
		buf.put((byte) '1');
		buf.put((byte) 0x01); // FORMAT_VERSION
		buf.putShort((short) width);
		buf.putShort((short) height);
		buf.put(bitmap);
		return buf.array();
	}

	private static byte[] drawCustomPayload(int x, int y, int width, int align, boolean wrap, byte[] rawGlyphIndices) {
		ByteBuffer payload = ByteBuffer.allocate(15 + rawGlyphIndices.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) width);
		payload.put((byte) 0xFF); // FONT_ID = custom
		payload.put((byte) Color.BLACK);
		payload.put((byte) TextBackground.TRANSPARENT);
		payload.put((byte) DrawMode.REPLACE);
		payload.put((byte) align);
		payload.put((byte) (wrap ? 1 : 0));
		payload.put((byte) WriteFlags.REFRESH_NOW);
		payload.putShort((short) rawGlyphIndices.length);
		payload.put(rawGlyphIndices);
		return payload.array();
	}

	private static byte[] setCustomFontFolderPayload(String pathWithPrefix) {
		byte[] pathBytes = pathWithPrefix.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(1 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		return payload.array();
	}

	private static void setCustomFontFolder(CommandClient client, String pathWithPrefix) throws Exception {
		send(client, CommandId.SET_CUSTOM_FONT_FOLDER, setCustomFontFolderPayload(pathWithPrefix));
	}

	private static boolean isSdPresent(CommandClient client) throws Exception {
		Frame response = client.send(CommandId.STORAGE_INFO_REQUEST, new byte[] { (byte) Volume.SD });
		return (response.getPayload()[1] & 0xFF) != 0;
	}

	private static void upload(CommandClient client, int volume, String path, byte[] content) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload =
				ByteBuffer.allocate(2 + pathBytes.length + 4 + content.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) volume);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		payload.putInt(content.length);
		payload.put(content);
		send(client, CommandId.FILE_UPLOAD, payload.array());
	}

	private static void expectNack(CommandClient client, int commandId, byte[] payload, int expectedStatus)
			throws Exception {
		try {
			client.send(commandId, payload);
			check("expected NACK(0x" + Integer.toHexString(expectedStatus) + ") but got ACK/response", false);
		} catch (CommandNackException e) {
			check("NACK status==0x" + Integer.toHexString(expectedStatus) + " (was 0x"
					+ Integer.toHexString(e.getStatus()) + ")", e.getStatus() == expectedStatus);
		}
	}

	private static void send(CommandClient client, int commandId, byte[] payload) throws Exception {
		try {
			client.send(commandId, payload);
			System.out.println("   ACKed");
		} catch (CommandNackException e) {
			System.err.println("FAILED: commandId=0x" + Integer.toHexString(commandId) + " NACK status=0x"
					+ Integer.toHexString(e.getStatus()));
			failures++;
		}
	}

	private static void check(String description, boolean condition) {
		if (condition) {
			System.out.println("   OK: " + description);
		} else {
			System.out.println("   FAIL: " + description);
			failures++;
		}
	}
}
