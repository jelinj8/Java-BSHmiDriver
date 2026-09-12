package cz.bliksoft.hmieink.protocol;

/**
 * Reports byte-level progress of a single {@link FrameTransport#send} call -
 * PC-side-only instrumentation for a large payload (e.g. {@code OTA_INSTALL},
 * {@code FILE_UPLOAD}), not a protocol-level chunk acknowledgment - the wire
 * protocol has no such thing (doc/PROTOCOL.md §16.1's whole image is one
 * logical frame; BLE fragmentation below that is invisible up here). Set via
 * {@link FrameTransport#setProgressListener}; invoked synchronously from
 * whichever thread calls {@link FrameTransport#send} (the caller's own thread,
 * not a background one).
 */
public interface TransferProgressListener {

	/**
	 * @param bytesSent  bytes written to the transport so far for this send,
	 *                   including this call's own contribution
	 * @param totalBytes total bytes in this send (the whole encoded frame,
	 *                   header/CRC included)
	 */
	void onProgress(long bytesSent, long totalBytes);
}
