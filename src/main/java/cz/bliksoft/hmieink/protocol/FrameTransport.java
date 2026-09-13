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

	/**
	 * Restores connectivity after the device has (or may have) rebooted on its own
	 * (e.g. {@code OTA_INSTALL}/{@code OTA_APPLY}/{@code OTA_ROLLBACK}, doc/
	 * PROTOCOL.md §16) - used by {@link HmiDevice#reconnect}. Default:
	 * {@link #close()} then {@link #connect()}, correct for a transport whose
	 * PC-side connection object doesn't survive a device-side reboot on its own
	 * (BLE's peripheral disconnects; a TCP socket resets).
	 *
	 * <p>
	 * {@code SerialFrameTransport} overrides this to do nothing at all: the CH340
	 * (or similar) USB-serial bridge chip stays enumerated and the host-side COM
	 * port stays open across a <em>target</em> reset - it's a separate chip from
	 * the ESP32 being reset. Closing and reopening the port would be actively
	 * harmful here, not just redundant: opening it asserts DTR/RTS, which is what
	 * resets the chip in the first place (see that class's doc) - doing that again
	 * while the device is sitting in a freshly-booted, not-yet-confirmed
	 * {@code PENDING_VERIFY} state (§16.4) resets it a second time before
	 * {@code OTA_CONFIRM} can ever be sent, which ESP-IDF's rollback logic
	 * correctly (if unhelpfully) treats as a failed boot and reverts - confirmed
	 * live on real hardware: an OTA that transferred and applied successfully was
	 * rolled back purely because reconnecting for the post-install confirm
	 * re-triggered this reset before confirming.
	 */
	default void reestablishAfterDeviceReboot() throws IOException {
		close();
		connect();
	}

	boolean isConnected();

	/** Stops the reader and releases the underlying connection. Idempotent. */
	@Override
	void close() throws IOException;
}
