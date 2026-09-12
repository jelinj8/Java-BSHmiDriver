package cz.bliksoft.hmieink.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

import com.fazecast.jSerialComm.SerialPortTimeoutException;

/**
 * Wraps a jSerialComm {@code InputStream} to present a normal
 * indefinitely-blocking stream to {@link FrameStreamReader}, working around a
 * real-hardware-observed quirk: jSerialComm's {@code TIMEOUT_READ_BLOCKING}
 * mode intermittently returned -1 (interpreted as stream EOF) on this CH340
 * adapter on Windows, while {@code TIMEOUT_READ_SEMI_BLOCKING} with a finite
 * per-call timeout reliably delivered data - just via a
 * {@link SerialPortTimeoutException} instead of a plain 0 whenever a single
 * read call's timeout elapses with nothing available. This class retries past
 * that exception (it's not a real error, just "nothing arrived in the last N
 * ms"), so from {@link FrameStreamReader}'s point of view this still just
 * blocks until data or a real I/O error. See {@link SerialFrameTransport} for
 * where it's used.
 */
final class RetryingBlockingInputStream extends InputStream {

	private final InputStream delegate;

	RetryingBlockingInputStream(InputStream delegate) {
		this.delegate = delegate;
	}

	@Override
	public int read() throws IOException {
		while (true) {
			checkInterrupted();
			try {
				return delegate.read();
			} catch (SerialPortTimeoutException e) {
				// no data within this port's configured per-call read timeout - not a real
				// error
			}
		}
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		while (true) {
			checkInterrupted();
			try {
				return delegate.read(b, off, len);
			} catch (SerialPortTimeoutException e) {
				// retry
			}
		}
	}

	@Override
	public void close() throws IOException {
		delegate.close();
	}

	private static void checkInterrupted() throws InterruptedIOException {
		if (Thread.currentThread().isInterrupted()) {
			throw new InterruptedIOException("reader thread interrupted");
		}
	}
}
