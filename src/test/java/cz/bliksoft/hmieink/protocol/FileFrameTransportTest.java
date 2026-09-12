package cz.bliksoft.hmieink.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileFrameTransportTest {

	@TempDir
	Path tempDir;

	@Test
	void sendSynchronouslyDeliversASyntheticAck() throws IOException {
		Path out = tempDir.resolve("out.macro");
		FileFrameTransport transport = new FileFrameTransport(out.toString());
		AtomicReference<Frame> received = new AtomicReference<>();
		transport.setListener(frame -> received.set(frame));
		transport.connect();

		transport.send(new Frame(CommandId.DRAW_RECT, 5, new byte[] { 1, 2, 3 }));

		Frame ack = received.get();
		assertEquals(CommandId.ACK, ack.getCommandId());
		byte[] p = ack.getPayload();
		assertEquals(5, p[0] & 0xFF);
		assertEquals(CommandId.DRAW_RECT & 0xFF, p[1] & 0xFF);
		assertEquals((CommandId.DRAW_RECT >> 8) & 0xFF, p[2] & 0xFF);
		assertEquals(Status.OK, p[3] & 0xFF);
	}

	@Test
	void closeWritesAValidMacroFile() throws IOException {
		Path out = tempDir.resolve("out.macro");
		FileFrameTransport transport = new FileFrameTransport(out.toString());
		transport.connect();
		transport.send(new Frame(CommandId.DRAW_RECT, 0, new byte[] { 1, 2, 3 }));
		transport.send(new Frame(CommandId.PAUSE, 1, new byte[] { 0, 0, 0, 0 }));
		transport.close();

		List<MacroCodec.Entry> entries = MacroCodec.decode(Files.readAllBytes(out));
		assertEquals(2, entries.size());
		assertEquals(CommandId.DRAW_RECT, entries.get(0).commandId);
		assertArrayEquals(new byte[] { 1, 2, 3 }, entries.get(0).payload);
		assertEquals(CommandId.PAUSE, entries.get(1).commandId);
	}

	@Test
	void commandClientSendCompletesImmediatelyOverAFileTransport() throws IOException {
		Path out = tempDir.resolve("out.macro");
		CommandClient client = new CommandClient(new FileFrameTransport(out.toString()), 1000);
		client.connect();
		try {
			// Would throw CommandTimeoutException within 1s if the synthetic ACK path were
			// broken.
			Frame response = client.send(CommandId.FAST_CLEAR, new byte[] { 0, 0 });
			assertEquals(CommandId.ACK, response.getCommandId());
		} finally {
			client.close();
		}
		assertTrue(Files.exists(out));
	}
}
