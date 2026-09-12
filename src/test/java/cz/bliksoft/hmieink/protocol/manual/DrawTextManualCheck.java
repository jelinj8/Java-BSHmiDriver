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
import cz.bliksoft.hmieink.protocol.TextAlign;
import cz.bliksoft.hmieink.protocol.TextBackground;

/**
 * Manual, real-hardware verification of DRAW_TEXT (doc/PROTOCOL.md §12.6): a
 * Czech pangram exercising nearly every accented glyph the embedded font
 * supports (WIDTH=0, single unbounded line), an ALL-CAPS Czech pangram (checks
 * whether accented capitals' diacritics have room above the glyph cell -
 * reported directly as broken for FONT_ID=0x01 in an earlier round, fixed by
 * using max_char_height+y_offset instead of ascent_A for that font's ascent), a
 * word-wrapped English paragraph (WIDTH&gt;0, WRAP=1), center/right-aligned
 * single lines (WIDTH&gt;0, WRAP=0), an opaque-background line, an inverted
 * (COLOR=WHITE + OPAQUE background) line (all FONT_ID=0x00), plus five
 * FONT_ID=0x01 (the larger u8g2_font_unifont_t_extended font, with real
 * precomposed Czech glyphs - no diacritic-overlay composition, unlike
 * FONT_ID=0x00) lines - the ALL-CAPS pangram, opaque background, inverted
 * (COLOR=WHITE + OPAQUE), and a DRAW_MODE=XOR self-cancel check (the same text
 * drawn twice at the same position, REPLACE then XOR - the ink should disappear
 * if DRAW_MODE compositing is genuinely applied per-pixel for this font, not
 * just for FONT_ID=0x00). All sent deferred, then one REFRESH(MODE=0x01). NOT
 * part of the automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.DrawTextManualCheck COM5
 * </pre>
 */
public final class DrawTextManualCheck {

	private DrawTextManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: DrawTextManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test - panel will briefly "
				+ "flash black then white before this test's own writes)...");
		client.connect();
		try {
			System.out.println("-> DRAW_TEXT: Czech pangram, unbounded single line, deferred");
			drawText(client, 5, 10, 0, TextAlign.LEFT, false, Color.BLACK, TextBackground.TRANSPARENT, DrawMode.REPLACE,
					0x00, "Příliš žluťoučký kůň úpěl ďábelské ódy");

			System.out.println("-> DRAW_TEXT: word-wrapped paragraph, WIDTH=200, WRAP=1, deferred");
			drawText(client, 5, 30, 200, TextAlign.LEFT, true, Color.BLACK, TextBackground.TRANSPARENT,
					DrawMode.REPLACE, 0x00,
					"The quick brown fox jumps over the lazy dog next to the CrowPanel display");

			System.out.println("-> DRAW_TEXT: FONT_ID=0x00, ALL-CAPS Czech pangram (checks whether accented capitals' "
					+ "diacritics have room above the classic font's own fixed-height glyph cell), deferred");
			drawText(client, 5, 70, 0, TextAlign.LEFT, false, Color.BLACK, TextBackground.TRANSPARENT, DrawMode.REPLACE,
					0x00, "PŘÍLIŠ ŽLUŤOUČKÝ KŮŇ ÚPLĚL");

			System.out.println("-> DRAW_TEXT: center-aligned, WIDTH=380, deferred");
			drawText(client, 10, 150, 380, TextAlign.CENTER, false, Color.BLACK, TextBackground.TRANSPARENT,
					DrawMode.REPLACE, 0x00, "Centered");

			System.out.println("-> DRAW_TEXT: right-aligned, WIDTH=380, deferred");
			drawText(client, 10, 170, 380, TextAlign.RIGHT, false, Color.BLACK, TextBackground.TRANSPARENT,
					DrawMode.REPLACE, 0x00, "Right-aligned");

			System.out.println("-> DRAW_TEXT: opaque background (black ink on white fill), deferred");
			drawText(client, 5, 190, 0, TextAlign.LEFT, false, Color.BLACK, TextBackground.OPAQUE, DrawMode.REPLACE,
					0x00, "Opaque background");

			System.out.println("-> DRAW_TEXT: inverted (white ink on black fill via COLOR=WHITE+OPAQUE), deferred");
			drawText(client, 5, 210, 0, TextAlign.LEFT, false, Color.WHITE, TextBackground.OPAQUE, DrawMode.REPLACE,
					0x00, "Inverted text");

			System.out.println("-> DRAW_TEXT: FONT_ID=0x01 (larger unifont_t_extended), ALL-CAPS Czech pangram - the "
					+ "lowercase case was already confirmed working, this specifically checks whether accented "
					+ "capitals' diacritics now have room above the cell (max_char_height+y_offset fix), deferred");
			drawText(client, 5, 230, 0, TextAlign.LEFT, false, Color.BLACK, TextBackground.TRANSPARENT,
					DrawMode.REPLACE, 0x01, "PŘÍLIŠ ŽLUŤOUČKÝ KŮŇ ÚPLĚL");

			System.out.println("-> DRAW_TEXT: FONT_ID=0x01, opaque background, deferred");
			drawText(client, 5, 252, 0, TextAlign.LEFT, false, Color.BLACK, TextBackground.OPAQUE, DrawMode.REPLACE,
					0x01, "Opaque 12pt");

			System.out.println("-> DRAW_TEXT: FONT_ID=0x01, inverted (white ink on black fill), deferred");
			drawText(client, 5, 274, 0, TextAlign.LEFT, false, Color.WHITE, TextBackground.OPAQUE, DrawMode.REPLACE,
					0x01, "Inverted 12pt");

			System.out.println("-> DRAW_TEXT: FONT_ID=0x01, DRAW_MODE=XOR self-cancel (same text, same position, "
					+ "REPLACE then XOR - should end up blank), deferred");
			drawText(client, 5, 296, 0, TextAlign.LEFT, false, Color.BLACK, TextBackground.TRANSPARENT,
					DrawMode.REPLACE, 0x01, "XOR twice");
			drawText(client, 5, 296, 0, TextAlign.LEFT, false, Color.BLACK, TextBackground.TRANSPARENT, DrawMode.XOR,
					0x01, "XOR twice");

			System.out.println("-> sending REFRESH(MODE=0x01) - all twelve text blocks should appear together");
			try {
				Frame response = client.send(CommandId.REFRESH, new byte[] { 0x01 }, 10_000);
				System.out.println("OK: device replied 0x" + Integer.toHexString(response.getCommandId())
						+ " - check the panel for the Czech pangram, the ALL-CAPS pangram (accented capitals' "
						+ "diacritics should NOT be clipped, for both FONT_ID=0x00 and the larger FONT_ID=0x01), a "
						+ "wrapped paragraph, centered/right-aligned lines, an opaque-background line, an inverted "
						+ "line, and the larger FONT_ID=0x01 lines (opaque background, inverted, and a BLANK row "
						+ "where the XOR self-cancel text should have disappeared)");
			} catch (CommandNackException e) {
				System.err.println("FAILED: REFRESH NACK status=0x" + Integer.toHexString(e.getStatus()));
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	private static void drawText(CommandClient client, int x, int y, int width, int align, boolean wrap, int color,
			int background, int drawMode, int fontId, String text) throws Exception {
		byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(15 + textBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) width);
		payload.put((byte) fontId);
		payload.put((byte) color);
		payload.put((byte) background);
		payload.put((byte) drawMode);
		payload.put((byte) align);
		payload.put((byte) (wrap ? 1 : 0));
		payload.put((byte) 0x00); // FLAGS: deferred
		payload.putShort((short) textBytes.length);
		payload.put(textBytes);

		try {
			Frame response = client.send(CommandId.DRAW_TEXT, payload.array());
			System.out.println("   ACKed (0x" + Integer.toHexString(response.getCommandId()) + ")");
		} catch (CommandNackException e) {
			System.err
					.println("FAILED: DRAW_TEXT \"" + text + "\" NACK status=0x" + Integer.toHexString(e.getStatus()));
			System.exit(1);
		}
	}
}
