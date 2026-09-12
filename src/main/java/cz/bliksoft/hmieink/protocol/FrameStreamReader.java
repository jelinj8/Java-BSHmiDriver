package cz.bliksoft.hmieink.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Reads {@link Frame}s one at a time out of a byte stream (doc/PROTOCOL.md
 * §3.2/§3.3 - TCP and Serial framing are identical: no chunking, PAYLOAD_LEN in
 * the Logical Frame header is already a length prefix). Shared by
 * {@link TcpFrameTransport} and {@link SerialFrameTransport}.
 *
 * <p>
 * Resync policy: bytes that don't start with MAGIC are skipped (handles leading
 * noise, e.g. from connecting mid-stream). Once a full header is buffered, its
 * declared PAYLOAD_LEN is trusted (bounded by {@link #MAX_REASONABLE_PAYLOAD}
 * as a sanity check) and that many bytes are read before attempting to decode;
 * if the resulting frame fails CRC (or the sanity bound was exceeded), only the
 * single leading MAGIC byte is dropped and scanning resumes from the next byte
 * - not the frame's full declared length, since a corrupted PAYLOAD_LEN can't
 * be trusted to skip the right number of bytes. Not thread-safe - one instance
 * per stream, read from one thread.
 */
final class FrameStreamReader {

	private static final int MAX_REASONABLE_PAYLOAD = 8 * 1024 * 1024;
	private static final int INITIAL_CAPACITY = 256;

	private final InputStream in;
	private byte[] buf = new byte[INITIAL_CAPACITY];
	private int len = 0;

	FrameStreamReader(InputStream in) {
		this.in = in;
	}

	/**
	 * Blocks until one full frame has been read (and CRC-validated) or the stream
	 * closes.
	 */
	Frame readFrame() throws IOException {
		while (true) {
			Frame frame = tryParse();
			if (frame != null) {
				return frame;
			}
			fillMore();
		}
	}

	private void fillMore() throws IOException {
		ensureCapacity(len + 1);
		int n = in.read(buf, len, buf.length - len);
		if (n < 0) {
			throw new EOFException("stream closed while reading a frame");
		}
		len += n;
	}

	private Frame tryParse() {
		while (true) {
			int skip = 0;
			while (skip < len && (buf[skip] & 0xFF) != Frame.MAGIC) {
				skip++;
			}
			if (skip > 0) {
				removeFront(skip);
			}
			if (len < Frame.HEADER_SIZE) {
				return null;
			}

			long payloadLen = readU32LE(buf, 5);
			if (payloadLen > MAX_REASONABLE_PAYLOAD) {
				removeFront(1);
				continue;
			}

			int total = Frame.HEADER_SIZE + (int) payloadLen + Frame.CRC_SIZE;
			if (len < total) {
				ensureCapacity(total);
				return null;
			}

			byte[] frameBytes = Arrays.copyOfRange(buf, 0, total);
			try {
				Frame frame = Frame.decode(frameBytes);
				removeFront(total);
				return frame;
			} catch (FrameException e) {
				removeFront(1);
			}
		}
	}

	private void removeFront(int n) {
		System.arraycopy(buf, n, buf, 0, len - n);
		len -= n;
	}

	private void ensureCapacity(int need) {
		if (need <= buf.length) {
			return;
		}
		int newCapacity = buf.length;
		while (newCapacity < need) {
			newCapacity *= 2;
		}
		buf = Arrays.copyOf(buf, newCapacity);
	}

	private static long readU32LE(byte[] b, int offset) {
		return (b[offset] & 0xFFL) | ((b[offset + 1] & 0xFFL) << 8) | ((b[offset + 2] & 0xFFL) << 16)
				| ((b[offset + 3] & 0xFFL) << 24);
	}
}
