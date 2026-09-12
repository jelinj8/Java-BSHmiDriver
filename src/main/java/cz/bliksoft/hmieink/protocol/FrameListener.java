package cz.bliksoft.hmieink.protocol;

import java.io.IOException;

/**
 * Receives frames pushed asynchronously by a {@link FrameTransport}'s
 * background reader.
 */
public interface FrameListener {

	/**
	 * Called on the transport's reader thread for every successfully decoded frame.
	 */
	void onFrame(Frame frame);

	/**
	 * Called once, on the reader thread, when the transport's read loop terminates
	 * - either because {@link FrameTransport#close()} was called (cause is
	 * {@code null}) or because the underlying connection failed/closed unexpectedly
	 * (cause is non-null).
	 */
	default void onTransportClosed(IOException cause) {
	}
}
