package cz.bliksoft.hmieink.protocol.font;

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds byte-value to Unicode-codepoint maps for
 * {@link GlyphGenerator#generateGlyphSet}, for codepages where each byte
 * 0x00-0xFF maps to at most one Unicode codepoint - true of every single-byte
 * Windows/ISO codepage, including CP1250 (Central European).
 */
public final class Codepages {

	private Codepages() {
	}

	/**
	 * Maps every byte in {@code [firstByte, lastByte]} through {@code charsetName}
	 * (a JDK {@link Charset} name, e.g. {@code "windows-1250"}) to its Unicode
	 * codepoint, skipping bytes the charset has no defined mapping for (Java
	 * decodes those to U+FFFD) - those codepage slots simply get no glyph file and
	 * fall back to {@code XX} on-device (doc/PROTOCOL.md §12.6.1).
	 */
	public static Map<Integer, Integer> singleByteCharsetRange(String charsetName, int firstByte, int lastByte) {
		Charset charset = Charset.forName(charsetName);
		Map<Integer, Integer> byteToCodepoint = new LinkedHashMap<>();
		for (int b = firstByte; b <= lastByte; b++) {
			int codepoint = new String(new byte[] { (byte) b }, charset).codePointAt(0);
			if (codepoint != '�') {
				byteToCodepoint.put(b, codepoint);
			}
		}
		return byteToCodepoint;
	}
}
