package cz.bliksoft.hmieink.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Shared implementation for the two byte-stream transports (TCP, Serial - doc/PROTOCOL.md
 * §3.2/§3.3): once a subclass hands over an {@link InputStream}/{@link OutputStream} pair via
 * {@link #beginReading}, this runs the background reader loop, dispatches frames to the current
 * {@link FrameListener}, and serializes concurrent {@link #send}s.
 */
abstract class AbstractStreamFrameTransport implements FrameTransport {

	private volatile InputStream in;
	private volatile OutputStream out;
	private volatile Thread readerThread;
	private volatile FrameListener listener;
	private final Object writeLock = new Object();

	protected final void beginReading(InputStream in, OutputStream out, String threadName) {
		this.in = in;
		this.out = out;
		Thread t = new Thread(this::readLoop, threadName);
		t.setDaemon(true);
		this.readerThread = t;
		t.start();
	}

	private void readLoop() {
		FrameStreamReader reader = new FrameStreamReader(in);
		IOException closeCause = null;
		try {
			while (!Thread.currentThread().isInterrupted()) {
				Frame frame = reader.readFrame();
				FrameListener l = listener;
				if (l != null) {
					l.onFrame(frame);
				}
			}
		} catch (IOException e) {
			closeCause = e;
		} finally {
			FrameListener l = listener;
			if (l != null) {
				l.onTransportClosed(closeCause);
			}
		}
	}

	@Override
	public final void send(Frame frame) throws IOException {
		sendRawBytes(frame.encode());
	}

	/**
	 * Writes raw bytes directly to the stream, sharing the same lock as {@link #send(Frame)} so a
	 * caller writing out-of-band bytes (e.g. {@code SerialFrameTransport}'s LOW_POWER wake preamble,
	 * doc/PROTOCOL.md §17.1) can't interleave with a concurrent frame send.
	 */
	protected final void sendRawBytes(byte[] data) throws IOException {
		OutputStream o = out;
		if (o == null) {
			throw new IOException("not connected");
		}
		synchronized (writeLock) {
			o.write(data);
			o.flush();
		}
	}

	@Override
	public final void setListener(FrameListener listener) {
		this.listener = listener;
	}

	@Override
	public final boolean isConnected() {
		return in != null;
	}

	/** Interrupts the reader thread and clears the stream references. Subclasses still own closing the underlying connection. */
	protected final void stopReading() {
		Thread t = readerThread;
		if (t != null) {
			t.interrupt();
		}
		in = null;
		out = null;
	}
}
