package cz.bliksoft.hmieink.protocol.font;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlyphGeneratorTest {

	@TempDir
	Path tempDir;

	private static Font logicalFont() {
		// A JDK logical font (always available, no external TTF file needed) - fine for testing the
		// generator's own packing/trimming/file-writing logic, which is font-content-agnostic.
		return new Font(Font.SANS_SERIF, Font.PLAIN, 16);
	}

	@Test
	void toGlyBytesRoundTripsThroughFirmwareHeaderLayout() {
		byte[] bitmap = { (byte) 0b10110000, (byte) 0b01000000 };
		RasterGlyph glyph = new RasterGlyph(5, 2, bitmap);

		byte[] encoded = GlyphGenerator.toGlyBytes(glyph);

		assertEquals(9 + bitmap.length, encoded.length);
		assertArrayEquals(new byte[] { 'G', 'L', 'Y', '1' }, java.util.Arrays.copyOfRange(encoded, 0, 4));
		assertEquals(1, encoded[4]); // FORMAT_VERSION
		assertEquals(5, (encoded[5] & 0xFF) | ((encoded[6] & 0xFF) << 8)); // WIDTH, u16 LE
		assertEquals(2, (encoded[7] & 0xFF) | ((encoded[8] & 0xFF) << 8)); // HEIGHT, u16 LE
		assertArrayEquals(bitmap, java.util.Arrays.copyOfRange(encoded, 9, encoded.length));
	}

	@Test
	void writeGlyFileCreatesMissingParentDirectories() throws Exception {
		Path file = tempDir.resolve("nested/deeper/41.gly");
		GlyphGenerator.writeGlyFile(file, new RasterGlyph(1, 1, new byte[] { 0 }));
		assertTrue(Files.exists(file));
	}

	@Test
	void fallbackGlyphKeepsFullUntrimmedCellHeight() {
		Font font = logicalFont();
		int ascent = 14;
		int cellHeight = 19;
		RasterGlyph fallback = GlyphGenerator.renderFallbackGlyph(font, '?', ascent, cellHeight, 0);
		assertEquals(cellHeight, fallback.height, "XX must stay untrimmed - it defines the line height itself");
	}

	@Test
	void ordinaryGlyphIsTrimmedToAtMostFullCellHeight() {
		Font font = logicalFont();
		int ascent = 14;
		int cellHeight = 19;
		RasterGlyph glyph = GlyphGenerator.renderGlyph(font, 'a', ascent, cellHeight, 0, true);
		assertTrue(glyph.height <= cellHeight);
		assertTrue(glyph.height >= 1, "even a blank glyph must keep at least one row (parseGlyFile rejects height=0)");
	}

	@Test
	void blankGlyphKeepsExactlyOneRow() {
		Font font = logicalFont();
		// U+00A0 NO-BREAK SPACE renders no ink in any reasonable font - the "fully blank" path.
		RasterGlyph glyph = GlyphGenerator.renderGlyph(font, ' ', 14, 19, 0, true);
		assertEquals(1, glyph.height);
	}

	@Test
	void fixedWidthForcesUniformWidthAcrossDifferentlyShapedGlyphs() {
		Font font = logicalFont();
		RasterGlyph narrow = GlyphGenerator.renderGlyph(font, 'i', 14, 19, 20, true);
		RasterGlyph wide = GlyphGenerator.renderGlyph(font, 'm', 14, 19, 20, true);
		assertEquals(20, narrow.width);
		assertEquals(20, wide.width);
	}

	@Test
	void generateGlyphSetWritesOneFilePerByteAndTheFallback() throws Exception {
		Font font = logicalFont();
		Map<Integer, Integer> byteToCodepoint = new LinkedHashMap<>();
		byteToCodepoint.put(0x41, (int) 'A');
		byteToCodepoint.put(0x62, (int) 'b');

		GlyphGenerator.generateGlyphSet(font, byteToCodepoint, '?', 0, tempDir);

		assertTrue(Files.exists(tempDir.resolve("41.gly")));
		assertTrue(Files.exists(tempDir.resolve("62.gly")));
		assertTrue(Files.exists(tempDir.resolve("XX.gly")));
	}

	@Test
	void maxNaturalWidthIsAtLeastAsWideAsEveryMemberGlyph() {
		Font font = logicalFont();
		java.util.List<Integer> codepoints = java.util.Arrays.asList((int) 'i', (int) 'm', (int) 'W');
		int max = GlyphGenerator.maxNaturalWidth(font, codepoints);
		for (RasterGlyph g : new RasterGlyph[] { GlyphGenerator.renderGlyph(font, 'i', 14, 19, 0, false),
				GlyphGenerator.renderGlyph(font, 'm', 14, 19, 0, false),
				GlyphGenerator.renderGlyph(font, 'W', 14, 19, 0, false) }) {
			assertTrue(g.width <= max);
		}
	}
}
