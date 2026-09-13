package cz.bliksoft.hmieink.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class CodepagesTest {

	@Test
	void asciiRangeMapsBytesToIdenticalCodepoints() {
		Map<Integer, Integer> map = Codepages.singleByteCharsetRange("windows-1250", 0x41, 0x5A);
		assertEquals(26, map.size());
		assertEquals((int) 'A', map.get(0x41));
		assertEquals((int) 'Z', map.get(0x5A));
	}

	@Test
	void windows1250UpperHalfMapsCzechDiacritics() {
		Map<Integer, Integer> map = Codepages.singleByteCharsetRange("windows-1250", 0x80, 0xFF);
		// 0xE8 is 'č' (U+010D) in windows-1250 - a concrete, well-known checkpoint
		// value.
		assertEquals(0x010D, (int) map.get(0xE8));
	}

	@Test
	void undefinedCodepageSlotsAreOmittedRatherThanMappedToReplacementChar() {
		Map<Integer, Integer> map = Codepages.singleByteCharsetRange("windows-1250", 0x80, 0xFF);
		// 0x81 is one of windows-1250's own undefined byte values.
		assertFalse(map.containsKey(0x81));
		assertTrue(map.size() < 0x80); // not every byte in the upper half is defined
	}
}
