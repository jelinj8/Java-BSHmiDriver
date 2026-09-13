package cz.bliksoft.hmieink.protocol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Minimal in-memory {@link FrameTransport} test double for
 * {@link CommandClientTest} - no real I/O. {@code send()} synchronously invokes
 * the installed responder and, if it returns non-null, delivers that frame to
 * the listener before returning - since {@link CommandClient} sets up its
 * pending-response state before calling {@code transport.send()}, this
 * deterministically exercises the same code path a real async transport would,
 * without any test-thread timing.
 */
public final class FakeFrameTransport implements FrameTransport {

	private final List<Frame> sent = new ArrayList<>();
	private FrameListener listener;
	private boolean connected;
	private Function<Frame, Frame> responder = request -> null; // no response by default
	private int connectFailuresRemaining;
	private int connectAttempts;

	public List<Frame> getSent() {
		return sent;
	}

	/**
	 * Makes the next {@code times} {@link #connect()} calls throw
	 * {@link IOException} before succeeding - for exercising retry logic (e.g.
	 * {@link HmiDevice#reconnect}) without any real I/O.
	 */
	public void failConnectTimes(int times) {
		this.connectFailuresRemaining = times;
	}

	public int getConnectAttempts() {
		return connectAttempts;
	}

	/**
	 * Installs a function computing the (possibly null, meaning "no response")
	 * reply to each sent request.
	 */
	public void setResponder(Function<Frame, Frame> responder) {
		this.responder = responder;
	}

	/**
	 * Delivers a frame to the installed listener without it being a response to any
	 * send() - simulates a device-pushed event.
	 */
	public void pushUnsolicited(Frame frame) {
		if (listener != null) {
			listener.onFrame(frame);
		}
	}

	@Override
	public void connect() throws IOException {
		connectAttempts++;
		if (connectFailuresRemaining > 0) {
			connectFailuresRemaining--;
			throw new IOException("simulated connect failure");
		}
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
