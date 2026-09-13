package cz.bliksoft.hmieink.font;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rasterizes a TTF/system {@link Font} into the custom-font glyph set DRAW_TEXT
 * FONT_ID=0xFF consumes (doc/PROTOCOL.md §12.6.1, firmware-BSHMIEinkDevice
 * repository - one {@code .gly} file per codepage byte 0x00-0xFF, plus the
 * mandatory {@code XX.gly} fallback/line-height-reference glyph).
 *
 * <p>
 * Every glyph is rendered onto a canvas {@code cellHeight} pixels tall (the
 * font's own ascent+descent at the chosen point size), with the character
 * positioned at its correct baseline - then trimmed to remove blank rows from
 * the <em>top only</em>, never the bottom. This is what makes device-side
 * bottom-alignment (§12.6.1: "bottom aligned, use XX as line height reference")
 * produce typographically correct results even for descenders (g/j/p/q/y): a
 * descender's ink genuinely reaches near the cell's bottom row, so it's never
 * trimmed away, while a glyph with no descender (most of them) naturally has a
 * shorter saved bitmap whose own bottom row already sits exactly on the
 * baseline - bottom-pinning it on-device reproduces the same baseline position
 * the glyph was rendered at, without needing any ascent/descent metadata in the
 * wire format itself. {@code XX} is the one exception - saved at the full,
 * untrimmed {@code cellHeight}, since it exists specifically to define what
 * that height <em>is</em>.
 */
public final class GlyphGenerator {

	private GlyphGenerator() {
	}

	public static final byte[] GLY_MAGIC = { 'G', 'L', 'Y', '1' };
	public static final int GLY_FORMAT_VERSION = 0x01;

	/** Loads a TTF file and derives it to {@code pointSize}. */
	public static Font loadFont(Path ttfFile, float pointSize) throws IOException, FontFormatException {
		try (InputStream in = Files.newInputStream(ttfFile)) {
			return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(pointSize);
		}
	}

	/**
	 * Rasterizes one Unicode codepoint (BMP only - this project's codepage-based
	 * custom font has no use for astral codepoints). {@code fixedWidth}, if
	 * {@code > 0}, forces this canvas width with the glyph horizontally centered
	 * (for a monospace-look variant of an otherwise proportional font);
	 * {@code <= 0} uses the font's own natural advance width for this character.
	 * {@code trimTop=false} keeps the full {@code cellHeight} untrimmed - only
	 * {@link #renderFallbackGlyph} needs that.
	 */
	public static RasterGlyph renderGlyph(Font font, int codepoint, int ascent, int cellHeight, int fixedWidth,
			boolean trimTop) {
		FontMetrics metrics = metricsOf(font);
		int naturalWidth = metrics.charWidth(codepoint);
		int canvasWidth = Math.max(1, fixedWidth > 0 ? fixedWidth : naturalWidth);
		int drawX = fixedWidth > 0 ? Math.max(0, (fixedWidth - naturalWidth) / 2) : 0;

		BufferedImage canvas = new BufferedImage(canvasWidth, cellHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(font);
			g.setColor(Color.BLACK);
			g.drawString(new String(Character.toChars(codepoint)), drawX, ascent);
		} finally {
			g.dispose();
		}

		int topInkRow = trimTop ? findTopInkRow(canvas) : 0;
		int glyphHeight = cellHeight - topInkRow;
		return new RasterGlyph(canvasWidth, glyphHeight, packBits(canvas, topInkRow, canvasWidth, glyphHeight));
	}

	/**
	 * The {@code XX} fallback/line-height-reference glyph - see this class's own
	 * doc for why it's untrimmed.
	 */
	public static RasterGlyph renderFallbackGlyph(Font font, int fallbackCodepoint, int ascent, int cellHeight,
			int fixedWidth) {
		return renderGlyph(font, fallbackCodepoint, ascent, cellHeight, fixedWidth, false);
	}

	private static int findTopInkRow(BufferedImage canvas) {
		int width = canvas.getWidth();
		int height = canvas.getHeight();
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				if (((canvas.getRGB(x, y) >>> 24) & 0xFF) >= 128) {
					return y;
				}
			}
		}
		return height - 1; // fully blank (e.g. space) - keep exactly one (blank) row, never zero
	}

	private static byte[] packBits(BufferedImage canvas, int topRow, int width, int height) {
		int bytesPerRow = (width + 7) / 8;
		byte[] bitmap = new byte[bytesPerRow * height];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int alpha = (canvas.getRGB(x, topRow + y) >>> 24) & 0xFF;
				if (alpha >= 128) {
					bitmap[y * bytesPerRow + x / 8] |= (byte) (0x80 >> (x % 8));
				}
			}
		}
		return bitmap;
	}

	/**
	 * Serializes one {@link RasterGlyph} to the exact {@code .gly} byte layout
	 * (doc/PROTOCOL.md §12.6.1).
	 */
	public static byte[] toGlyBytes(RasterGlyph glyph) {
		ByteBuffer buf = ByteBuffer.allocate(9 + glyph.packedBitmap.length).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(GLY_MAGIC);
		buf.put((byte) GLY_FORMAT_VERSION);
		buf.putShort((short) glyph.width);
		buf.putShort((short) glyph.height);
		buf.put(glyph.packedBitmap);
		return buf.array();
	}

	public static void writeGlyFile(Path file, RasterGlyph glyph) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, toGlyBytes(glyph));
	}

	/**
	 * Generates a full glyph set - one {@code <NN>.gly} per entry of
	 * {@code byteToCodepoint} (NN = 2 uppercase hex digits of the map's key,
	 * 0x00-0xFF) plus {@code XX.gly} for {@code fallbackCodepoint} - into
	 * {@code outputDir}. {@code fixedWidth <= 0} means proportional (each glyph
	 * keeps the font's own natural advance width); {@code > 0} forces every glyph
	 * (fallback included) to that width, centered.
	 */
	public static void generateGlyphSet(Font font, Map<Integer, Integer> byteToCodepoint, int fallbackCodepoint,
			int fixedWidth, Path outputDir) throws IOException {
		FontMetrics metrics = metricsOf(font);
		int ascent = metrics.getAscent();
		int cellHeight = ascent + metrics.getDescent();

		RasterGlyph fallback = renderFallbackGlyph(font, fallbackCodepoint, ascent, cellHeight, fixedWidth);
		writeGlyFile(outputDir.resolve("XX.gly"), fallback);

		for (Map.Entry<Integer, Integer> entry : new TreeMap<>(byteToCodepoint).entrySet()) {
			int codepageByte = entry.getKey();
			if (codepageByte < 0 || codepageByte > 0xFF) {
				throw new IllegalArgumentException("codepage byte out of range: " + codepageByte);
			}
			RasterGlyph glyph = renderGlyph(font, entry.getValue(), ascent, cellHeight, fixedWidth, true);
			writeGlyFile(outputDir.resolve(hexBaseName(codepageByte) + ".gly"), glyph);
		}
	}

	/**
	 * {@link BdfFont} counterpart of
	 * {@link #generateGlyphSet(Font, Map, int, int, Path)} - a codepage byte whose
	 * codepoint isn't covered by {@code font} is skipped (reported on
	 * {@code System.out}) rather than forced to some substitute, letting the
	 * device's own {@code XX} fallback handle it at draw time (doc/PROTOCOL.md
	 * §12.6.1) exactly as a missing/corrupt individual glyph file already would.
	 */
	public static void generateGlyphSet(BdfFont font, Map<Integer, Integer> byteToCodepoint, int fallbackCodepoint,
			int fixedWidth, Path outputDir) throws IOException {
		if (!font.hasGlyph(fallbackCodepoint)) {
			throw new IllegalArgumentException(
					"BDF font has no glyph for the fallback codepoint U+" + Integer.toHexString(fallbackCodepoint));
		}
		writeGlyFile(outputDir.resolve("XX.gly"), font.renderFallbackGlyph(fallbackCodepoint, fixedWidth));

		for (Map.Entry<Integer, Integer> entry : new TreeMap<>(byteToCodepoint).entrySet()) {
			int codepageByte = entry.getKey();
			if (codepageByte < 0 || codepageByte > 0xFF) {
				throw new IllegalArgumentException("codepage byte out of range: " + codepageByte);
			}
			int codepoint = entry.getValue();
			if (!font.hasGlyph(codepoint)) {
				System.out.println("  (skipping codepage byte " + hexBaseName(codepageByte) + " / U+"
						+ Integer.toHexString(codepoint).toUpperCase(Locale.ROOT)
						+ " - not covered by this BDF font, will fall back to XX on-device)");
				continue;
			}
			writeGlyFile(outputDir.resolve(hexBaseName(codepageByte) + ".gly"),
					font.renderGlyph(codepoint, fixedWidth, true));
		}
	}

	/**
	 * The widest natural advance width across a set of codepoints, at this
	 * font/size - a sensible {@code fixedWidth} for a monospace-look variant that
	 * clips nothing.
	 */
	public static int maxNaturalWidth(Font font, Iterable<Integer> codepoints) {
		FontMetrics metrics = metricsOf(font);
		int max = 1;
		for (int cp : codepoints) {
			max = Math.max(max, metrics.charWidth(cp));
		}
		return max;
	}

	private static String hexBaseName(int codepageByte) {
		String hex = Integer.toHexString(codepageByte).toUpperCase(Locale.ROOT);
		return hex.length() == 1 ? "0" + hex : hex;
	}

	private static FontMetrics metricsOf(Font font) {
		BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = scratch.createGraphics();
		try {
			g.setFont(font);
			return g.getFontMetrics();
		} finally {
			g.dispose();
		}
	}
}
