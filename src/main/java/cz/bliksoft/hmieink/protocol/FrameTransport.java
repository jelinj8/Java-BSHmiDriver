package cz.bliksoft.hmieink.protocol;

import java.io.Closeable;
import java.io.IOException;

/**
 * A connection to a CrowPanel device over one of the transports in doc/PROTOCOL.md §1 (TCP,
 * Serial, or - once implemented - BLE), sending and receiving {@link Frame}s. This is
 * transport-layer only (§1 Layer 1/2: framing and delivery); request/response correlation,
 * stop-and-wait retry, and command payload parsing are a layer above this, not yet implemented.
 *
 * <p>
 * Implementations dispatch every received frame to the current {@link FrameListener} on a private
 * background thread - callers that need to correlate a response to a specific request must do so
 * themselves (e.g. by matching {@code commandId}/{@code seq} in their listener).
 */
public interface FrameTransport extends Closeable {

	/** Establishes the underlying connection and starts the background reader. Blocks until connected or failed. */
	void connect() throws IOException;

	/** Encodes and writes one frame. Safe to call from any thread; concurrent sends are serialized. */
	void send(Frame frame) throws IOException;

	/** Replaces the frame listener. May be called before or after {@link #connect()}. */
	void setListener(FrameListener listener);

	boolean isConnected();

	/** Stops the reader and releases the underlying connection. Idempotent. */
	@Override
	void close() throws IOException;
}
