package cz.bliksoft.hmieink.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class MacroCodecTest {

	@Test
	void roundTripsMultipleEntries() {
		List<MacroCodec.Entry> entries = Arrays.asList(
				new MacroCodec.Entry(CommandId.DRAW_RECT, new byte[] { 1, 2, 3 }),
				new MacroCodec.Entry(CommandId.PAUSE, new byte[] { 0, 0, 0, 0 }),
				new MacroCodec.Entry(CommandId.REFRESH, new byte[] { 0x01 }));

		List<MacroCodec.Entry> decoded = MacroCodec.decode(MacroCodec.encode(entries));

		assertEquals(3, decoded.size());
		for (int i = 0; i < entries.size(); i++) {
			assertEquals(entries.get(i).commandId, decoded.get(i).commandId);
			assertArrayEquals(entries.get(i).payload, decoded.get(i).payload);
		}
	}

	@Test
	void roundTripsEmptyMacro() {
		List<MacroCodec.Entry> decoded = MacroCodec.decode(MacroCodec.encode(Arrays.asList()));
		assertEquals(0, decoded.size());
	}

	@Test
	void roundTripsEntryWithEmptyPayload() {
		List<MacroCodec.Entry> decoded = MacroCodec
				.decode(MacroCodec.encode(Arrays.asList(new MacroCodec.Entry(CommandId.RECORD_MACRO, new byte[0]))));
		assertEquals(1, decoded.size());
		assertEquals(0, decoded.get(0).payload.length);
	}

	@Test
	void decodeRejectsBadMagic() {
		byte[] bogus = new byte[10];
		assertThrows(IllegalArgumentException.class, () -> MacroCodec.decode(bogus));
	}

	@Test
	void decodeRejectsTruncatedEntry() {
		byte[] macro = MacroCodec
				.encode(Arrays.asList(new MacroCodec.Entry(CommandId.PAUSE, new byte[] { 1, 2, 3, 4 })));
		byte[] truncated = Arrays.copyOf(macro, macro.length - 2);
		assertThrows(IllegalArgumentException.class, () -> MacroCodec.decode(truncated));
	}
}
