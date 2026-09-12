package cz.bliksoft.hmieink.protocol;

import java.io.Closeable;
import java.io.IOException;

/**
 * A connection to a CrowPanel device over one of the transports in
 * doc/PROTOCOL.md §1 (TCP, Serial, or - once implemented - BLE), sending and
 * receiving {@link Frame}s. This is transport-layer only (§1 Layer 1/2: framing
 * and delivery); request/response correlation, stop-and-wait retry, and command
 * payload parsing are a layer above this, not yet implemented.
 *
 * <p>
 * Implementations dispatch every received frame to the current
 * {@link FrameListener} on a private background thread - callers that need to
 * correlate a response to a specific request must do so themselves (e.g. by
 * matching {@code commandId}/{@code seq} in their listener).
 */
public interface FrameTransport extends Closeable {

	/**
	 * Establishes the underlying connection and starts the background reader.
	 * Blocks until connected or failed.
	 */
	void connect() throws IOException;

	/**
	 * Encodes and writes one frame. Safe to call from any thread; concurrent sends
	 * are serialized.
	 */
	void send(Frame frame) throws IOException;

	/**
	 * Replaces the frame listener. May be called before or after
	 * {@link #connect()}.
	 */
	void setListener(FrameListener listener);

	/**
	 * Sets a listener that receives byte-level progress during {@link #send} for a
	 * large payload (e.g. {@code OTA_INSTALL}, {@code FILE_UPLOAD}) - PC-side
	 * transfer progress only, not a protocol-level chunk acknowledgment (see
	 * {@link TransferProgressListener}). {@code null} clears it. Default no-op;
	 * only the stream-based transports (Serial/TCP/BLE, via
	 * {@code AbstractStreamFrameTransport}) currently report progress.
	 */
	default void setProgressListener(TransferProgressListener listener) {
	}

	/**
	 * Applies a chunk size negotiated via {@code HANDSHAKE_RESPONSE}'s
	 * {@code MAX_CHUNK_SIZE} capability (doc/PROTOCOL.md §5.2) to this transport,
	 * if it has a chunk size to negotiate at all. Default no-op; only
	 * {@code BleFrameTransport} currently has one (BLE's ATT MTU bounds how much
	 * fits in one characteristic write - Serial/TCP have no equivalent limit).
	 * {@code maxChunkSize <= 0} means "no limit reported" and should be ignored by
	 * the implementation.
	 */
	default void setMaxChunkSize(int maxChunkSize) {
	}

	boolean isConnected();

	/** Stops the reader and releases the underlying connection. Idempotent. */
	@Override
	void close() throws IOException;
}
