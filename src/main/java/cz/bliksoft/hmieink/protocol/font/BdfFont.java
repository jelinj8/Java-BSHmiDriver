package cz.bliksoft.hmieink.protocol.font;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a BDF (Glyph Bitmap Distribution Format) bitmap font - e.g. Terminus Font
 * (terminus-font.sourceforge.net, OFL 1.1) - directly into {@link RasterGlyph}s for the same
 * {@code .gly} pipeline {@link GlyphGenerator}'s TTF path feeds (doc/PROTOCOL.md §12.6.1). Unlike a
 * TTF, a BDF glyph is already exact 1bpp pixel data - no antialiasing/rasterization/thresholding
 * happens or is needed anywhere in this class; {@link #renderGlyph} only repositions each glyph's
 * own bounding box into a shared {@code cellHeight}-tall canvas (by its {@code BBX} y-offset
 * relative to the font's baseline, mirroring how {@link GlyphGenerator#renderGlyph} positions a TTF
 * glyph at its own baseline via {@code Graphics2D.drawString}) and applies the same "trim blank rows
 * off the top only" rule {@link GlyphGenerator}'s own class doc explains (what makes device-side
 * bottom-alignment correct for descenders without any baseline metadata in the wire format).
 *
 * <p>
 * Only as much of the BDF spec as real-world monospace ("character cell", {@code SPACING "C"})
 * fonts actually use is implemented: {@code FONT_ASCENT}/{@code FONT_DESCENT}/{@code DEFAULT_CHAR}
 * properties, and {@code STARTCHAR}/{@code ENCODING}/{@code DWIDTH}/{@code BBX}/{@code BITMAP}/
 * {@code ENDCHAR} per glyph - no XLFD font-name parsing, no {@code SWIDTH} (unused - {@code DWIDTH}
 * is the real pixel advance), no vertical-writing fields.
 */
public final class BdfFont {

	private final int ascent;
	private final int descent;
	private final int defaultCodepoint;
	private final Map<Integer, Glyph> glyphsByCodepoint;

	private BdfFont(int ascent, int descent, int defaultCodepoint, Map<Integer, Glyph> glyphsByCodepoint) {
		this.ascent = ascent;
		this.descent = descent;
		this.defaultCodepoint = defaultCodepoint;
		this.glyphsByCodepoint = glyphsByCodepoint;
	}

	public int cellHeight() {
		return ascent + descent;
	}

	public int defaultCodepoint() {
		return defaultCodepoint;
	}

	public boolean hasGlyph(int codepoint) {
		return glyphsByCodepoint.containsKey(codepoint);
	}

	public static BdfFont load(Path bdfFile) throws IOException {
		// BDF is a plain-ASCII text format (the spec allows ISO-8859-1 in STARTPROPERTIES string
		// values, never used by the fields this parser reads) - ISO_8859_1 decodes every byte
		// without throwing, which plain US-ASCII content round-trips through unchanged.
		List<String> lines = Files.readAllLines(bdfFile, StandardCharsets.ISO_8859_1);
		int ascent = -1;
		int descent = -1;
		int defaultCodepoint = '?';
		Map<Integer, Glyph> glyphs = new HashMap<>();

		int i = 0;
		while (i < lines.size()) {
			String line = lines.get(i);
			if (line.startsWith("FONT_ASCENT ")) {
				ascent = Integer.parseInt(line.substring("FONT_ASCENT ".length()).trim());
			} else if (line.startsWith("FONT_DESCENT ")) {
				descent = Integer.parseInt(line.substring("FONT_DESCENT ".length()).trim());
			} else if (line.startsWith("DEFAULT_CHAR ")) {
				defaultCodepoint = Integer.parseInt(line.substring("DEFAULT_CHAR ".length()).trim());
			} else if (line.startsWith("STARTCHAR ")) {
				i = parseGlyph(bdfFile, lines, i, glyphs);
				continue;
			}
			i++;
		}
		if (ascent < 0 || descent < 0) {
			throw new IOException(bdfFile + ": missing FONT_ASCENT/FONT_DESCENT (not a valid BDF font, or one "
					+ "missing the properties this parser relies on)");
		}
		return new BdfFont(ascent, descent, defaultCodepoint, glyphs);
	}

	private static int parseGlyph(Path bdfFile, List<String> lines, int startIndex, Map<Integer, Glyph> out)
			throws IOException {
		int i = startIndex + 1;
		int encoding = -1;
		int dwidth = 0;
		int bbw = 0;
		int bbh = 0;
		int xoff = 0;
		int yoff = 0;
		while (i < lines.size()) {
			String line = lines.get(i);
			if (line.startsWith("ENCODING ")) {
				encoding = Integer.parseInt(line.substring("ENCODING ".length()).trim().split("\\s+")[0]);
			} else if (line.startsWith("DWIDTH ")) {
				dwidth = Integer.parseInt(line.substring("DWIDTH ".length()).trim().split("\\s+")[0]);
			} else if (line.startsWith("BBX ")) {
				String[] parts = line.substring("BBX ".length()).trim().split("\\s+");
				bbw = Integer.parseInt(parts[0]);
				bbh = Integer.parseInt(parts[1]);
				xoff = Integer.parseInt(parts[2]);
				yoff = Integer.parseInt(parts[3]);
			} else if (line.equals("BITMAP")) {
				int bytesPerRow = (bbw + 7) / 8;
				byte[] bitmap = new byte[bytesPerRow * bbh];
				for (int row = 0; row < bbh; row++) {
					i++;
					String hex = lines.get(i).trim();
					for (int b = 0; b < bytesPerRow; b++) {
						bitmap[row * bytesPerRow + b] = (byte) Integer.parseInt(hex.substring(b * 2, b * 2 + 2), 16);
					}
				}
				i += 2; // consumed BITMAP's own data rows, then skip the ENDCHAR line
				if (encoding >= 0) { // -1 means "no standard encoding" (BDF spec) - not addressable, skip
					out.put(encoding, new Glyph(dwidth, bbw, bbh, xoff, yoff, bitmap));
				}
				return i;
			}
			i++;
		}
		throw new IOException(bdfFile + ": STARTCHAR at line " + (startIndex + 1) + " has no BITMAP/ENDCHAR");
	}

	/**
	 * Renders one codepoint into the same "canvas" contract {@link GlyphGenerator#renderGlyph}
	 * produces: {@code cellHeight} tall, {@code fixedWidth} wide if {@code >0} else this glyph's own
	 * {@code DWIDTH}, its bits blitted at their correct baseline-relative row/column, trimmed from
	 * the top only when {@code trimTop}. Throws if this font doesn't cover {@code codepoint} - check
	 * {@link #hasGlyph} first (callers generating a whole set should skip an uncovered codepoint
	 * rather than force some substitute, letting the device's own {@code XX} fallback handle it -
	 * see {@link GlyphGenerator#generateGlyphSet(BdfFont, Map, int, int, Path)}).
	 */
	public RasterGlyph renderGlyph(int codepoint, int fixedWidth, boolean trimTop) {
		Glyph glyph = glyphsByCodepoint.get(codepoint);
		if (glyph == null) {
			throw new IllegalArgumentException(
					"this BDF font has no glyph for codepoint U+" + Integer.toHexString(codepoint));
		}
		int cellHeight = cellHeight();
		int canvasWidth = Math.max(1, fixedWidth > 0 ? fixedWidth : glyph.dwidth);
		int bytesPerRow = (canvasWidth + 7) / 8;
		byte[] canvas = new byte[bytesPerRow * cellHeight];

		// BBX's yoff is the glyph bitmap's bottom-left corner's offset from the baseline (positive =
		// above it, negative = below, e.g. a descender) - so its top row sits (yoff+bbh) rows above
		// the baseline; canvas row 0 is the cell's top, `ascent` rows above the baseline.
		int glyphTopCanvasRow = ascent - (glyph.yoff + glyph.bbh);
		int glyphBytesPerRow = (glyph.bbw + 7) / 8;
		for (int row = 0; row < glyph.bbh; row++) {
			int canvasRow = glyphTopCanvasRow + row;
			if (canvasRow < 0 || canvasRow >= cellHeight) {
				continue; // glyph extends outside the cell - not expected from a well-formed font
			}
			for (int col = 0; col < glyph.bbw; col++) {
				boolean ink = (glyph.bitmap[row * glyphBytesPerRow + col / 8] & (0x80 >> (col % 8))) != 0;
				if (!ink) {
					continue;
				}
				int canvasCol = glyph.xoff + col;
				if (canvasCol < 0 || canvasCol >= canvasWidth) {
					continue; // clipped by a narrower fixedWidth override than this glyph's own bbox
				}
				canvas[canvasRow * bytesPerRow + canvasCol / 8] |= (byte) (0x80 >> (canvasCol % 8));
			}
		}

		int topInkRow = trimTop ? findTopInkRow(canvas, bytesPerRow, cellHeight) : 0;
		int glyphHeight = cellHeight - topInkRow;
		byte[] trimmed = new byte[bytesPerRow * glyphHeight];
		System.arraycopy(canvas, topInkRow * bytesPerRow, trimmed, 0, trimmed.length);
		return new RasterGlyph(canvasWidth, glyphHeight, trimmed);
	}

	/** The {@code XX} fallback/line-height-reference glyph - see {@link GlyphGenerator}'s class doc for why
	 * it's untrimmed. */
	public RasterGlyph renderFallbackGlyph(int fallbackCodepoint, int fixedWidth) {
		return renderGlyph(fallbackCodepoint, fixedWidth, false);
	}

	private static int findTopInkRow(byte[] canvas, int bytesPerRow, int height) {
		for (int row = 0; row < height; row++) {
			for (int b = 0; b < bytesPerRow; b++) {
				if (canvas[row * bytesPerRow + b] != 0) {
					return row;
				}
			}
		}
		return height - 1; // fully blank - keep exactly one (blank) row, never zero
	}

	private static final class Glyph {
		final int dwidth;
		final int bbw;
		final int bbh;
		final int xoff;
		final int yoff;
		final byte[] bitmap;

		Glyph(int dwidth, int bbw, int bbh, int xoff, int yoff, byte[] bitmap) {
			this.dwidth = dwidth;
			this.bbw = bbw;
			this.bbh = bbh;
			this.xoff = xoff;
			this.yoff = yoff;
			this.bitmap = bitmap;
		}
	}
}
