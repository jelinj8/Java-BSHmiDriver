package cz.bliksoft.hmieink.protocol.text;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.WriteFlags;
import cz.bliksoft.hmieink.protocol.schema.CommandSchema;
import cz.bliksoft.hmieink.protocol.schema.PayloadCodec;

class TextCommandFormatTest {

	@TempDir
	Path tempDir;

	@Test
	void parsesSimpleCommand() {
		TextCommandFormat.ParsedCommand parsed = TextCommandFormat.parse("FAST_CLEAR|BLACK|REFRESH_NOW", '|');
		assertEquals(CommandId.FAST_CLEAR, parsed.commandId);
		Map<String, Object> fields = PayloadCodec.decode(CommandSchema.byId(CommandId.FAST_CLEAR), parsed.payload);
		assertEquals((long) Color.BLACK, fields.get("COLOR"));
		assertEquals((long) WriteFlags.REFRESH_NOW, fields.get("FLAGS"));
	}

	@Test
	void formatsBackToTheSameText() {
		TextCommandFormat.ParsedCommand parsed = TextCommandFormat.parse("FAST_CLEAR|BLACK|REFRESH_NOW", '|');
		assertEquals("FAST_CLEAR|BLACK|REFRESH_NOW", TextCommandFormat.format(parsed.commandId, parsed.payload, '|'));
	}

	@Test
	void resolvesEnumNamesCaseInsensitively() {
		TextCommandFormat.ParsedCommand parsed = TextCommandFormat.parse("FAST_CLEAR|black|0", '|');
		Map<String, Object> fields = PayloadCodec.decode(CommandSchema.byId(CommandId.FAST_CLEAR), parsed.payload);
		assertEquals((long) Color.BLACK, fields.get("COLOR"));
	}

	@Test
	void parsesAndFormatsPlusJoinedBitmask() {
		TextCommandFormat.ParsedCommand parsed = TextCommandFormat
				.parse("DRAW_RECT|10|20|30|40|BLACK|REPLACE|true|1|REFRESH_NOW+REFRESH_FULL", '|');
		Map<String, Object> fields = PayloadCodec.decode(CommandSchema.byId(CommandId.DRAW_RECT), parsed.payload);
		assertEquals((long) (WriteFlags.REFRESH_NOW | WriteFlags.REFRESH_FULL), fields.get("FLAGS"));
		assertEquals("DRAW_RECT|10|20|30|40|BLACK|REPLACE|1|1|REFRESH_NOW+REFRESH_FULL",
				TextCommandFormat.format(parsed.commandId, parsed.payload, '|'));
	}

	@Test
	void escapesRoundTripThroughReparse() {
		String line = "DRAW_TEXT|10|20|0|0|BLACK|TRANSPARENT|REPLACE|LEFT|false|0|Line1\\nLine2\\twith\\|pipe and \\u00E9";
		TextCommandFormat.ParsedCommand parsed = TextCommandFormat.parse(line, '|');
		Map<String, Object> fields = PayloadCodec.decode(CommandSchema.byId(CommandId.DRAW_TEXT), parsed.payload);
		assertEquals("Line1\nLine2\twith|pipe and é", fields.get("TEXT"));

		String formatted = TextCommandFormat.format(parsed.commandId, parsed.payload, '|');
		TextCommandFormat.ParsedCommand reparsed = TextCommandFormat.parse(formatted, '|');
		assertArrayEquals(parsed.payload, reparsed.payload);
	}

	@Test
	void atFileTokenLoadsRawBytes() throws IOException {
		Path file = tempDir.resolve("marker.bin");
		byte[] content = { 1, 2, 3, 4, 5 };
		Files.write(file, content);

		TextCommandFormat.ParsedCommand parsed = TextCommandFormat.parse("LOG_MESSAGE|@" + file, '|');
		Map<String, Object> fields = PayloadCodec.decode(CommandSchema.byId(CommandId.LOG_MESSAGE), parsed.payload);
		assertArrayEquals(content, (byte[]) fields.get("MARKER"));
	}

	@Test
	void plainTextMarkerDoesNotNeedAtFile() {
		TextCommandFormat.ParsedCommand parsed = TextCommandFormat.parse("LOG_MESSAGE|init_done", '|');
		Map<String, Object> fields = PayloadCodec.decode(CommandSchema.byId(CommandId.LOG_MESSAGE), parsed.payload);
		assertArrayEquals("init_done".getBytes(StandardCharsets.UTF_8), (byte[]) fields.get("MARKER"));
	}

	@Test
	void customSeparatorWorks() {
		TextCommandFormat.ParsedCommand parsed = TextCommandFormat
				.parse("DRAW_RECT;10;20;30;40;BLACK;REPLACE;true;1;REFRESH_NOW", ';');
		Map<String, Object> fields = PayloadCodec.decode(CommandSchema.byId(CommandId.DRAW_RECT), parsed.payload);
		assertEquals(10L, fields.get("X"));
		assertEquals((long) WriteFlags.REFRESH_NOW, fields.get("FLAGS"));
	}

	@Test
	void missingFieldThrows() {
		assertThrows(IllegalArgumentException.class, () -> TextCommandFormat.parse("DRAW_RECT|10|20", '|'));
	}

	@Test
	void tooManyFieldsThrows() {
		assertThrows(IllegalArgumentException.class, () -> TextCommandFormat.parse("PAUSE|100|200", '|'));
	}

	@Test
	void unknownCommandNameThrows() {
		assertThrows(IllegalArgumentException.class, () -> TextCommandFormat.parse("NOT_A_REAL_COMMAND|1|2", '|'));
	}

	@Test
	void decodeOnlyCommandCannotBeParsedAsSendable() {
		assertThrows(IllegalArgumentException.class, () -> TextCommandFormat.parse("BUTTON_EVENT|1|0|1000", '|'));
	}

	@Test
	void repeatedStringTailConsumesRemainingTokens() {
		TextCommandFormat.ParsedCommand parsed = TextCommandFormat
				.parse("DRAW_IMAGE_ROW|0|0|0|LEFT|4|REPLACE|0|INTERNAL|a.epi|b.epi|c.epi", '|');
		Map<String, Object> fields = PayloadCodec.decode(CommandSchema.byId(CommandId.DRAW_IMAGE_ROW), parsed.payload);
		assertEquals(3L, fields.get("COUNT"));
		assertEquals(java.util.Arrays.asList("a.epi", "b.epi", "c.epi"), fields.get("PATHS"));
	}
}
