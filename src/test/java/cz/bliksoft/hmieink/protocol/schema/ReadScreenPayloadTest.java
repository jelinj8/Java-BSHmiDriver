package cz.bliksoft.hmieink.protocol.schema;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.ReadScreenMode;
import cz.bliksoft.hmieink.protocol.ReadScreenSource;
import cz.bliksoft.hmieink.text.TextCommandFormat;

/**
 * READ_SCREEN's X/Y/WIDTH/HEIGHT are on the wire only for MODE=REGION
 * (doc/PROTOCOL.md §8) - the firmware rejects a 10-byte FULL request with
 * BAD_PARAMETERS.
 */
class ReadScreenPayloadTest {

	private static final CommandSpec SPEC = CommandSchema.byName("READ_SCREEN");

	@Test
	void fullModeEncodesSourceAndModeOnly() {
		Map<String, Object> fields = fields(ReadScreenMode.FULL);
		assertArrayEquals(new byte[] { ReadScreenSource.WORKING_BUFFER, ReadScreenMode.FULL },
				PayloadCodec.encode(SPEC, fields));
	}

	@Test
	void regionModeEncodesTheRegion() {
		Map<String, Object> fields = fields(ReadScreenMode.REGION);
		assertArrayEquals(
				new byte[] { ReadScreenSource.WORKING_BUFFER, ReadScreenMode.REGION, 1, 0, 2, 0, (byte) 0x90, 1, 3, 0 },
				PayloadCodec.encode(SPEC, fields));
	}

	@Test
	void textFormatSkipsTheRegionForFullMode() {
		TextCommandFormat.ParsedCommand full = TextCommandFormat.parse("READ_SCREEN|WORKING_BUFFER|FULL", '|');
		assertEquals(2, full.payload.length);
		assertEquals("READ_SCREEN|WORKING_BUFFER|FULL",
				TextCommandFormat.format(CommandId.READ_SCREEN, full.payload, '|'));
		assertThrows(IllegalArgumentException.class,
				() -> TextCommandFormat.parse("READ_SCREEN|WORKING_BUFFER|FULL|0|0|400|300", '|'));

		TextCommandFormat.ParsedCommand region = TextCommandFormat.parse("READ_SCREEN|PANEL|REGION|1|2|400|3", '|');
		assertEquals(10, region.payload.length);
		assertEquals("READ_SCREEN|PANEL|REGION|1|2|400|3",
				TextCommandFormat.format(CommandId.READ_SCREEN, region.payload, '|'));
	}

	private static Map<String, Object> fields(int mode) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("SOURCE", (long) ReadScreenSource.WORKING_BUFFER);
		m.put("MODE", (long) mode);
		m.put("X", 1L);
		m.put("Y", 2L);
		m.put("WIDTH", 400L);
		m.put("HEIGHT", 3L);
		return m;
	}
}
