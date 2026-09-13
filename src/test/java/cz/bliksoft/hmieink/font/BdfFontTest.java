package cz.bliksoft.hmieink.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BdfFontTest {

	@TempDir
	Path tempDir;

	// FONT_ASCENT=6, FONT_DESCENT=2 -> cellHeight=8. 'A' (0x41): BBX 4 6 0 0 (no
	// descender, occupies
	// the ascent zone only, canvas rows 0-5) with its own top 2 rows deliberately
	// blank, to exercise
	// top-trimming. '?' (0x3F, DEFAULT_CHAR): BBX 4 8 0 -2 (a "descender" reaching
	// 2 rows below the
	// baseline, canvas rows 0-7 = the whole cell) with ink in its own top row, to
	// exercise the
	// untrimmed-fallback path.
	private static final String SYNTHETIC_BDF = "STARTFONT 2.1\n" + "FONT test\n" + "SIZE 8 75 75\n"
			+ "FONTBOUNDINGBOX 4 8 0 -2\n" + "STARTPROPERTIES 2\n" + "FONT_ASCENT 6\n" + "FONT_DESCENT 2\n"
			+ "DEFAULT_CHAR 63\n" + "ENDPROPERTIES\n" + "CHARS 2\n" + "STARTCHAR A\n" + "ENCODING 65\n"
			+ "SWIDTH 500 0\n" + "DWIDTH 4 0\n" + "BBX 4 6 0 0\n" + "BITMAP\n" + "00\n" + "00\n" + "60\n" + "90\n"
			+ "F0\n" + "90\n" + "ENDCHAR\n" + "STARTCHAR question\n" + "ENCODING 63\n" + "SWIDTH 500 0\n"
			+ "DWIDTH 4 0\n" + "BBX 4 8 0 -2\n" + "BITMAP\n" + "60\n" + "90\n" + "10\n" + "20\n" + "20\n" + "00\n"
			+ "20\n" + "00\n" + "ENDCHAR\n" + "ENDFONT\n";

	private BdfFont load() throws IOException {
		Path file = tempDir.resolve("test.bdf");
		Files.write(file, SYNTHETIC_BDF.getBytes(StandardCharsets.ISO_8859_1));
		return BdfFont.load(file);
	}

	@Test
	void parsesFontMetrics() throws IOException {
		BdfFont font = load();
		assertEquals(8, font.cellHeight());
		assertEquals('?', font.defaultCodepoint());
	}

	@Test
	void hasGlyphReflectsCoverage() throws IOException {
		BdfFont font = load();
		assertTrue(font.hasGlyph('A'));
		assertTrue(font.hasGlyph('?'));
		assertFalse(font.hasGlyph('B'));
	}

	@Test
	void missingCodepointThrows() throws IOException {
		BdfFont font = load();
		assertThrows(IllegalArgumentException.class, () -> font.renderGlyph('B', 0, true));
	}

	@Test
	void trimmedGlyphSkipsOnlyBlankTopRows() throws IOException {
		BdfFont font = load();
		// 'A': cellHeight=8, its own bitmap occupies canvas rows 0-5 (BBX yoff=0,bbh=6
		// -> top row =
		// ascent(6)-(0+6)=0), with its own first 2 rows blank -> first real ink at
		// canvas row 2.
		RasterGlyph glyph = font.renderGlyph('A', 0, true);
		assertEquals(4, glyph.width);
		assertEquals(6, glyph.height); // cellHeight(8) - topInkRow(2)
	}

	@Test
	void fallbackGlyphIsNeverTrimmed() throws IOException {
		BdfFont font = load();
		RasterGlyph fallback = font.renderFallbackGlyph('?', 0);
		assertEquals(8, fallback.height); // full cellHeight, even though its own first row has ink
	}

	@Test
	void fixedWidthWidensCanvasAndClipsIfNarrower() throws IOException {
		BdfFont font = load();
		RasterGlyph wide = font.renderGlyph('A', 10, false);
		assertEquals(10, wide.width);
		RasterGlyph narrow = font.renderGlyph('A', 2, false); // narrower than the glyph's own BBX width (4)
		assertEquals(2, narrow.width); // clipped, not shrunk - matches GlyphGenerator's TTF-path contract
	}

	@Test
	void generateGlyphSetSkipsUncoveredCodepointsInsteadOfFailing() throws IOException {
		BdfFont font = load();
		java.util.Map<Integer, Integer> byteToCodepoint = new java.util.LinkedHashMap<>();
		byteToCodepoint.put(0x41, (int) 'A');
		byteToCodepoint.put(0x42, (int) 'B'); // not covered by this synthetic font - must be skipped, not throw

		GlyphGenerator.generateGlyphSet(font, byteToCodepoint, '?', 0, tempDir.resolve("out"));

		assertTrue(Files.exists(tempDir.resolve("out/41.gly")));
		assertFalse(Files.exists(tempDir.resolve("out/42.gly")));
		assertTrue(Files.exists(tempDir.resolve("out/XX.gly")));
	}
}
