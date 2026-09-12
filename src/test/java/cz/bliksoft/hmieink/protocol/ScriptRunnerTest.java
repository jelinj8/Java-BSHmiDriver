package cz.bliksoft.hmieink.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import cz.bliksoft.hmieink.protocol.script.ScriptRunner;

/**
 * In the same package as {@link CommandClientTest} specifically to reuse its
 * {@link FakeFrameTransport} test double (package-private) rather than
 * inventing a second one.
 */
class ScriptRunnerTest {

	private static final PrintStream QUIET = new PrintStream(new OutputStream() {
		@Override
		public void write(int b) {
		}
	});

	@Test
	void sleepBlocksLocallyAndSendsNothingToTheDevice() throws IOException {
		FakeFrameTransport transport = new FakeFrameTransport();
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		long start = System.currentTimeMillis();
		runner.runLine("SLEEP|150", '|');
		long elapsed = System.currentTimeMillis() - start;

		assertTrue(elapsed >= 150, "SLEEP should block for at least the requested duration, took " + elapsed + "ms");
		assertTrue(transport.getSent().isEmpty(), "SLEEP must never send anything to the device");
	}

	@Test
	void sleepIsCaseInsensitive() throws IOException {
		FakeFrameTransport transport = new FakeFrameTransport();
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		runner.runLine("sleep|10", '|');

		assertTrue(transport.getSent().isEmpty());
	}

	@Test
	void waitLogWithoutMarkerMatchesAnyLogMessage() throws Exception {
		FakeFrameTransport transport = new FakeFrameTransport();
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		Thread pusher = new Thread(() -> {
			sleepUnchecked(50);
			transport.pushUnsolicited(new Frame(CommandId.LOG_MESSAGE, 0, "anything".getBytes(StandardCharsets.UTF_8)));
		});
		pusher.start();

		runner.runLine("WAIT_LOG|2000", '|'); // must not throw
		pusher.join();
	}

	@Test
	void waitLogWithMarkerIgnoresNonMatchingMessages() throws Exception {
		FakeFrameTransport transport = new FakeFrameTransport();
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		Thread pusher = new Thread(() -> {
			sleepUnchecked(30);
			transport.pushUnsolicited(new Frame(CommandId.LOG_MESSAGE, 0, "wrong".getBytes(StandardCharsets.UTF_8)));
			sleepUnchecked(30);
			transport
					.pushUnsolicited(new Frame(CommandId.LOG_MESSAGE, 1, "boot_done".getBytes(StandardCharsets.UTF_8)));
		});
		pusher.start();

		runner.runLine("WAIT_LOG|2000|boot_done", '|'); // must not throw
		pusher.join();
	}

	@Test
	void waitLogTimesOutIfNothingArrives() {
		FakeFrameTransport transport = new FakeFrameTransport();
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		assertThrows(IOException.class, () -> runner.runLine("WAIT_LOG|100", '|'));
	}

	@Test
	void ordinaryCommandsStillGoToTheDevice() throws IOException {
		FakeFrameTransport transport = new FakeFrameTransport();
		transport.setResponder(request -> new Frame(CommandId.ACK, request.getSeq(),
				new byte[] { (byte) request.getSeq(), 0, 0, Status.OK }));
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		runner.runLine("FAST_CLEAR|WHITE|0", '|');

		assertEquals(1, transport.getSent().size());
		assertEquals(CommandId.FAST_CLEAR, transport.getSent().get(0).getCommandId());
	}

	private static void sleepUnchecked(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
