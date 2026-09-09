package cz.bliksoft.hmieink.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Minimal in-memory {@link FrameTransport} test double for {@link CommandClientTest} - no real
 * I/O. {@code send()} synchronously invokes the installed responder and, if it returns non-null,
 * delivers that frame to the listener before returning - since {@link CommandClient} sets up its
 * pending-response state before calling {@code transport.send()}, this deterministically exercises
 * the same code path a real async transport would, without any test-thread timing.
 */
final class FakeFrameTransport implements FrameTransport {

	private final List<Frame> sent = new ArrayList<>();
	private FrameListener listener;
	private boolean connected;
	private Function<Frame, Frame> responder = request -> null; // no response by default

	List<Frame> getSent() {
		return sent;
	}

	/** Installs a function computing the (possibly null, meaning "no response") reply to each sent request. */
	void setResponder(Function<Frame, Frame> responder) {
		this.responder = responder;
	}

	/** Delivers a frame to the installed listener without it being a response to any send() - simulates a device-pushed event. */
	void pushUnsolicited(Frame frame) {
		if (listener != null) {
			listener.onFrame(frame);
		}
	}

	@Override
	public void connect() {
		connected = true;
	}

	@Override
	public void send(Frame frame) {
		sent.add(frame);
		Frame response = responder.apply(frame);
		if (response != null && listener != null) {
			listener.onFrame(response);
		}
	}

	@Override
	public void setListener(FrameListener listener) {
		this.listener = listener;
	}

	@Override
	public boolean isConnected() {
		return connected;
	}

	@Override
	public void close() {
		connected = false;
	}
}
