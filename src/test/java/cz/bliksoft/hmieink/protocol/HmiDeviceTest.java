package cz.bliksoft.hmieink.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cz.bliksoft.hmieink.protocol.text.TextCommandFormat;

class HmiDeviceTest {

	@TempDir
	Path tempDir;

	@Test
	void sendTextThenDescribeRoundTrips() throws IOException {
		Path out = tempDir.resolve("out.macro");
		try (HmiDevice device = new FileHmiDevice(out.toString())) {
			device.connect();
			Frame ack = device.sendText("FAST_CLEAR|BLACK|REFRESH_NOW");
			assertEquals(CommandId.ACK, ack.getCommandId());
		}
		List<MacroCodec.Entry> entries = MacroCodec.decode(Files.readAllBytes(out));
		assertEquals(1, entries.size());
		assertEquals("FAST_CLEAR|BLACK|REFRESH_NOW",
				TextCommandFormat.format(entries.get(0).commandId, entries.get(0).payload, '|'));
	}

	/**
	 * Mirrors the CLI's own -f/-c/-p handling: a sequence of sendText() calls, in
	 * order, must appear in that exact order in the resulting .macro file - the
	 * guarantee Cli's hand-rolled args[] walk relies on instead of picocli's own
	 * (order-losing) repeated-option collection.
	 */
	@Test
	void sequentialSendsPreserveOrder() throws IOException {
		Path out = tempDir.resolve("out.macro");
		try (HmiDevice device = new FileHmiDevice(out.toString())) {
			device.connect();
			device.logMessage("header"); // simulates a -f header file's one line
			device.sendText("PAUSE|1000"); // simulates one -c inline command
			device.logMessage("piped"); // simulates a -p piped line
			device.logMessage("footer"); // simulates a -f footer file's one line
		}
		List<MacroCodec.Entry> entries = MacroCodec.decode(Files.readAllBytes(out));
		assertEquals(4, entries.size());
		assertEquals("header", new String(entries.get(0).payload, java.nio.charset.StandardCharsets.UTF_8));
		assertEquals(CommandId.PAUSE, entries.get(1).commandId);
		assertEquals("piped", new String(entries.get(2).payload, java.nio.charset.StandardCharsets.UTF_8));
		assertEquals("footer", new String(entries.get(3).payload, java.nio.charset.StandardCharsets.UTF_8));
	}
}
