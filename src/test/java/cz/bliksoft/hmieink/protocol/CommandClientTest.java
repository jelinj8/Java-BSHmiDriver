package cz.bliksoft.hmieink.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class CommandClientTest {

	@Test
	void ackIsReturnedAsResponse() throws Exception {
		FakeFrameTransport transport = new FakeFrameTransport();
		transport.setResponder(
				request -> new Frame(CommandId.ACK, request.getSeq(), new byte[] { (byte) request.getSeq(), 0, 0, Status.OK }));
		CommandClient client = new CommandClient(transport);

		Frame response = client.send(CommandId.DRAW_LINE, new byte[] { 1, 2, 3 });

		assertEquals(CommandId.ACK, response.getCommandId());
		assertEquals(1, transport.getSent().size());
	}

	@Test
	void nackThrowsCommandNackExceptionWithStatus() {
		FakeFrameTransport transport = new FakeFrameTransport();
		transport.setResponder(request -> {
			byte[] payload = { (byte) request.getSeq(), (byte) (request.getCommandId() & 0xFF),
					(byte) ((request.getCommandId() >> 8) & 0xFF), (byte) Status.BAD_PARAMETERS };
			return new Frame(CommandId.NACK, request.getSeq(), payload);
		});
		CommandClient client = new CommandClient(transport);

		CommandNackException ex = assertThrows(CommandNackException.class,
				() -> client.send(CommandId.DRAW_LINE, new byte[0]));
		assertEquals(Status.BAD_PARAMETERS, ex.getStatus());
		assertEquals(CommandId.DRAW_LINE, ex.getRefCommandId());
	}

	@Test
	void directResponseFrameCorrelatesByCommandIdAndSeq() throws Exception {
		FakeFrameTransport transport = new FakeFrameTransport();
		transport.setResponder(request -> new Frame(CommandId.HANDSHAKE_RESPONSE, request.getSeq(), new byte[] { 42 }));
		CommandClient client = new CommandClient(transport);

		Frame response = client.send(CommandId.HANDSHAKE_REQUEST, null);

		assertEquals(CommandId.HANDSHAKE_RESPONSE, response.getCommandId());
		assertEquals(1, response.getPayload().length);
		assertEquals(42, response.getPayload()[0]);
	}

	@Test
	void timesOutAndRetriesOnceThenThrows() {
		FakeFrameTransport transport = new FakeFrameTransport(); // default responder never replies
		CommandClient client = new CommandClient(transport, 50);

		CommandTimeoutException ex = assertThrows(CommandTimeoutException.class,
				() -> client.send(CommandId.DRAW_LINE, new byte[0]));
		assertEquals(CommandId.DRAW_LINE, ex.getCommandId());
		assertEquals(2, transport.getSent().size(), "should have sent the original request plus exactly one retry");
	}

	@Test
	void retrySucceedsIfSecondAttemptGetsAResponse() throws Exception {
		FakeFrameTransport transport = new FakeFrameTransport();
		AtomicInteger callCount = new AtomicInteger(0);
		transport.setResponder(request -> callCount.incrementAndGet() < 2 ? null
				: new Frame(CommandId.ACK, request.getSeq(), new byte[] { (byte) request.getSeq(), 0, 0, Status.OK }));
		CommandClient client = new CommandClient(transport, 50);

		Frame response = client.send(CommandId.DRAW_LINE, new byte[0]);

		assertEquals(CommandId.ACK, response.getCommandId());
		assertEquals(2, transport.getSent().size());
	}

	@Test
	void unsolicitedEventWithCollidingSeqIsNotMistakenForTheResponse() throws Exception {
		// The pending request's SEQ is 0 (first send() on a fresh client) - this event deliberately
		// reuses SEQ=0 too, proving correlation checks COMMAND_ID as well as SEQ (doc/PROTOCOL.md
		// §10: a device-initiated event frame has its own independent SEQ counter and could
		// legitimately collide with whatever the client's pending request happens to be using).
		FakeFrameTransport transport = new FakeFrameTransport();
		AtomicReference<Frame> receivedEvent = new AtomicReference<>();
		transport.setResponder(request -> {
			transport.pushUnsolicited(new Frame(CommandId.BUTTON_EVENT, 0, new byte[] { 1, 0, 0, 0, 0, 0 }));
			return new Frame(CommandId.ACK, request.getSeq(), new byte[] { (byte) request.getSeq(), 0, 0, Status.OK });
		});
		CommandClient client = new CommandClient(transport);
		client.addEventListener(receivedEvent::set);

		Frame response = client.send(CommandId.DRAW_LINE, new byte[0]);

		assertEquals(CommandId.ACK, response.getCommandId());
		assertEquals(CommandId.BUTTON_EVENT, receivedEvent.get().getCommandId());
	}

	@Test
	void waitForLogMessageRetroactivelyCatchesAnAlreadyArrivedMessage() throws Exception {
		// Regression test for a real bug found on real hardware: a macro's first entry can echo
		// back within microseconds of the triggering PLAY_MACRO's own ACK - well before a script's
		// *separate*, later waitForLogMessage() call gets a chance to register a live listener.
		// pushUnsolicited() here simulates that: the LOG_MESSAGE arrives (and, pre-fix, would have
		// been dropped) before waitForLogMessage() is ever called.
		FakeFrameTransport transport = new FakeFrameTransport();
		CommandClient client = new CommandClient(transport);
		byte[] marker = "boot_done".getBytes(java.nio.charset.StandardCharsets.UTF_8);

		transport.pushUnsolicited(new Frame(CommandId.LOG_MESSAGE, 0, marker));

		client.waitForLogMessage(marker, 1000); // must return immediately, not throw
	}

	@Test
	void waitForLogMessageWithoutMarkerRetroactivelyCatchesAnyAlreadyArrivedMessage() throws Exception {
		FakeFrameTransport transport = new FakeFrameTransport();
		CommandClient client = new CommandClient(transport);

		transport.pushUnsolicited(new Frame(CommandId.LOG_MESSAGE, 0, "anything".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

		client.waitForLogMessage(1000); // must return immediately, not throw
	}

	@Test
	void aRetroactivelyMatchedMessageIsConsumedOnlyOnce() throws Exception {
		FakeFrameTransport transport = new FakeFrameTransport();
		CommandClient client = new CommandClient(transport);
		byte[] marker = "boot_done".getBytes(java.nio.charset.StandardCharsets.UTF_8);

		transport.pushUnsolicited(new Frame(CommandId.LOG_MESSAGE, 0, marker));

		client.waitForLogMessage(marker, 1000); // consumes the buffered message
		assertThrows(java.io.IOException.class, () -> client.waitForLogMessage(marker, 50)); // nothing left - times out
	}
}
