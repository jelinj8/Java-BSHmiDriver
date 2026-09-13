package cz.bliksoft.hmieink.protocol.manual;

import java.awt.Font;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cz.bliksoft.hmieink.font.BdfFont;
import cz.bliksoft.hmieink.font.Codepages;
import cz.bliksoft.hmieink.font.GlyphGenerator;

/**
 * Generates the initial custom-font (DRAW_TEXT FONT_ID=0xFF, doc/PROTOCOL.md
 * §12.6.1) glyph sets for {@code firmware-BSHMIEinkDevice-resources/fonts/}
 * from the source TTF/BDF files vendored (alongside their licenses, for reuse)
 * in that same repo's own {@code font-sources/} - deliberately a sibling of
 * {@code fonts/}, not nested inside it, since {@code fonts/} is exactly what a
 * real {@code SYNC ... PC_MASTER} pushes onto a device's SD card (found the
 * hard way: an earlier layout with sources nested under {@code fonts/sources/}
 * got swept up and uploaded too, several hundred KB to MB of TTF/BDF/license
 * files a device has no use for, nearly 5x'ing a real sync's duration for no
 * benefit). Produces four sets:
 *
 * <ul>
 * <li>{@code cp1250-16px-proportional/} - full printable CP1250 range, 16pt
 * DejaVuSans-Regular, proportional (each glyph keeps its own natural advance
 * width, no {@code fixedWidthPx} needed - kept as a genuinely
 * no-width-specification option, not folded into the fixed-width one below: "We
 * still need to keep an option for proportional fonts (without the glyph width
 * specification)"). Originally Liberation Sans; switched to DejaVu Sans for the
 * same better-small-size-hinting reason as the fixed variant below (asked
 * directly: "Are there other proportional fonts with better hinting?" -
 * DejaVu's whole family, proportional included, is hinted noticeably better
 * than Liberation's at small pixel sizes; other genuinely open, well-hinted
 * candidates worth trying if this still isn't enough include Noto Sans (Google,
 * OFL, broad script coverage but heavier/larger at small sizes) and PT Sans
 * (ParaType, OFL) - neither vendored here yet, would need fetching first).
 * <li>{@code cp1250-16px-fixed/} - the same range/size, from
 * DejaVuSansMono-Regular (genuinely monospace by design, unlike Liberation Sans
 * - a first attempt force-fit Liberation Sans's proportional glyphs into one
 * shared cell by centering, which looked visibly space-padded around ordinary
 * letters no matter which specific width was chosen, since centering a
 * naturally-narrow glyph in a cell sized for wide outliers always leaves a
 * gap). {@code fixedWidthPx} is optional - omit it (or pass 0) to just use
 * DejaVu's own uniform natural width; a positive override deliberately
 * widens/narrows the cell instead (e.g. for extra letter-spacing).
 * <li>{@code cp1250-16px-terminus/} - the same range, from Terminus Font's 8x16
 * "u16n" bitmap strike (BDF, via {@link BdfFont}) instead of any TTF at all - a
 * true "matrix font": every pixel is exact, hand-designed source data, no
 * antialiasing/hinting-quality question even applies ("Antialiasing is
 * terrible... Can we use and convert some matrix fonts, like consolas from
 * Linux?"). OFL 1.1 licensed, ships across effectively every Linux distribution
 * ({@code xfonts-terminus}/{@code fonts-terminus}), 8px fixed advance -
 * narrower than either DejaVu variant at the same 16px cell height, so more
 * characters fit per line. A handful of rare codepoints outside Terminus's own
 * Unicode coverage are skipped (falls back to {@code XX} on-device) rather than
 * failing the whole generation - see
 * {@link GlyphGenerator#generateGlyphSet(BdfFont, Map, int, int, Path)}.
 * <li>{@code large-numeric/} - {@code 0-9:-/|\+_*!?} at 48pt
 * LiberationMono-Regular (already monospace; fixedWidth is passed anyway for
 * exact uniformity, e.g. against hinting rounding).
 * </ul>
 *
 * Not a hardware check (no device involved) - a one-shot generation tool, run
 * directly:
 *
 * <pre>
 * mvn test-compile
 * java -cp target/classes;target/test-classes \
 *     cz.bliksoft.hmieink.protocol.manual.GenerateDeviceFonts \
 *     C:\Users\jakub\work\Bliksoft\Java\GIT\firmware-BSHMIEinkDevice-resources\font-sources \
 *     C:\Users\jakub\work\Bliksoft\Java\GIT\firmware-BSHMIEinkDevice-resources\fonts
 * </pre>
 *
 * Optionally followed by a 3rd argument to override {@code cp1250-16px-fixed}'s
 * cell width in px (omit for DejaVu Sans Mono's own natural width).
 */
public final class GenerateDeviceFonts {

	private static final int CP1250_POINT_SIZE = 16;
	private static final int LARGE_NUMERIC_POINT_SIZE = 48;
	private static final int FALLBACK_CODEPOINT = '?';

	private GenerateDeviceFonts() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 2 && args.length != 3) {
			System.err.println("usage: GenerateDeviceFonts <source TTF/BDF dir, e.g. .../font-sources> "
					+ "<output fonts dir> [cp1250-16px-fixed's fixed glyph width, in px]");
			System.err.println("       (the 3rd argument is optional - DejaVu Sans Mono is genuinely monospace, "
					+ "so its own natural advance width is already the right value if omitted; a positive "
					+ "override is only useful to deliberately widen/narrow the cell, e.g. for extra "
					+ "letter-spacing, and takes the same 'renders flush-left and clips slightly if narrower "
					+ "than some glyph's natural width' handling GlyphGenerator always applies)");
			System.exit(2);
		}
		Path sourceFontsDir = Paths.get(args[0]);
		Path outputFontsDir = Paths.get(args[1]);
		int cp1250FixedWidthPx = args.length == 3 ? Integer.parseInt(args[2]) : 0;

		System.out.println("-- cp1250-16px-proportional --");
		generateCp1250FromTtf(sourceFontsDir.resolve("DejaVuSans.ttf"),
				outputFontsDir.resolve("cp1250-16px-proportional"), 0);

		System.out.println();
		System.out.println("-- cp1250-16px-fixed --");
		generateCp1250FromTtf(sourceFontsDir.resolve("DejaVuSansMono.ttf"), outputFontsDir.resolve("cp1250-16px-fixed"),
				cp1250FixedWidthPx);

		System.out.println();
		System.out.println("-- cp1250-16px-terminus --");
		generateCp1250FromBdf(sourceFontsDir.resolve("Terminus-16-u.bdf"),
				outputFontsDir.resolve("cp1250-16px-terminus"));

		System.out.println();
		System.out.println("-- large-numeric --");
		generateLargeNumeric(sourceFontsDir.resolve("LiberationMono-Regular.ttf"),
				outputFontsDir.resolve("large-numeric"));

		System.out.println();
		System.out.println("done.");
	}

	/**
	 * Full printable CP1250 range: 0x20-0x7E (ASCII, matches windows-1250 there),
	 * 0x7F (DEL) excluded, 0x80-0xFF (windows-1250's own upper half - Codepages
	 * skips byte values it leaves undefined, e.g. 0x81/0x83/0x88/0x90/0x98).
	 */
	private static Map<Integer, Integer> cp1250PrintableRange() {
		Map<Integer, Integer> byteToCodepoint = new LinkedHashMap<>();
		byteToCodepoint.putAll(Codepages.singleByteCharsetRange("windows-1250", 0x20, 0x7E));
		byteToCodepoint.putAll(Codepages.singleByteCharsetRange("windows-1250", 0x80, 0xFF));
		return byteToCodepoint;
	}

	/**
	 * {@code fixedWidth<=0} means proportional (each glyph keeps its own natural
	 * advance width).
	 */
	private static void generateCp1250FromTtf(Path ttfFile, Path outputDir, int fixedWidth) throws Exception {
		Font font = GlyphGenerator.loadFont(ttfFile, CP1250_POINT_SIZE);
		Map<Integer, Integer> byteToCodepoint = cp1250PrintableRange();
		System.out.println("glyphs to generate: " + byteToCodepoint.size() + " (+ XX fallback)"
				+ (fixedWidth > 0 ? ", fixed width=" + fixedWidth + "px" : ", proportional"));
		if (fixedWidth > 0) {
			// A glyph naturally wider than fixedWidth (rare upper-half symbols like ‰/™/em
			// dash/
			// ellipsis, 16-17px at this size/font) is not shrunk to fit -
			// GlyphGenerator.renderGlyph()
			// always canvases to exactly fixedWidth, so it renders flush-left and clips by
			// the
			// (small) excess instead, an acceptable tradeoff for characters this rare.
			int widest = GlyphGenerator.maxNaturalWidth(font, byteToCodepoint.values());
			if (widest > fixedWidth) {
				System.out.println("note: widest natural glyph in this set is " + widest
						+ "px - some rare symbols will clip slightly at fixed width " + fixedWidth + "px");
			}
		}
		GlyphGenerator.generateGlyphSet(font, byteToCodepoint, FALLBACK_CODEPOINT, fixedWidth, outputDir);
		System.out.println("wrote " + outputDir);
	}

	private static void generateCp1250FromBdf(Path bdfFile, Path outputDir) throws Exception {
		BdfFont font = BdfFont.load(bdfFile);
		Map<Integer, Integer> byteToCodepoint = cp1250PrintableRange();
		System.out.println("glyphs to generate: " + byteToCodepoint.size() + " (+ XX fallback), cell height="
				+ font.cellHeight() + "px, proportional (this font's own uniform character-cell advance width)");
		GlyphGenerator.generateGlyphSet(font, byteToCodepoint, FALLBACK_CODEPOINT, 0, outputDir);
		System.out.println("wrote " + outputDir);
	}

	private static void generateLargeNumeric(Path ttfFile, Path outputDir) throws Exception {
		Font font = GlyphGenerator.loadFont(ttfFile, LARGE_NUMERIC_POINT_SIZE);
		String chars = "0123456789:-/|\\+_*!?";
		Map<Integer, Integer> byteToCodepoint = new LinkedHashMap<>();
		List<Integer> codepoints = new ArrayList<>();
		for (int i = 0; i < chars.length(); i++) {
			int codepoint = chars.charAt(i);
			byteToCodepoint.put(codepoint, codepoint); // ASCII subset: codepage byte == codepoint
			codepoints.add(codepoint);
		}
		System.out
				.println("glyphs to generate: " + byteToCodepoint.size() + " (+ XX fallback), chars=\"" + chars + "\"");

		int fixedWidth = GlyphGenerator.maxNaturalWidth(font, codepoints);
		System.out.println("fixed width: " + fixedWidth + "px (LiberationMono is already monospace - this just "
				+ "guards against any per-glyph hinting rounding)");
		GlyphGenerator.generateGlyphSet(font, byteToCodepoint, FALLBACK_CODEPOINT, fixedWidth, outputDir);
		System.out.println("wrote " + outputDir);
	}
}
