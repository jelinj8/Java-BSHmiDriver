package cz.bliksoft.hmieink.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Shared implementation for the two byte-stream transports (TCP, Serial -
 * doc/PROTOCOL.md §3.2/§3.3): once a subclass hands over an
 * {@link InputStream}/{@link OutputStream} pair via {@link #beginReading}, this
 * runs the background reader loop, dispatches frames to the current
 * {@link FrameListener}, and serializes concurrent {@link #send}s.
 */
abstract class AbstractStreamFrameTransport implements FrameTransport {

	private volatile InputStream in;
	private volatile OutputStream out;
	private volatile Thread readerThread;
	private volatile FrameListener listener;
	private volatile TransferProgressListener progressListener;
	private final Object writeLock = new Object();

	/**
	 * Chunk size used only when a {@link TransferProgressListener} is set (see
	 * {@link #sendRawBytes}).
	 */
	private static final int PROGRESS_CHUNK_SIZE = 4096;

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
	 * Writes raw bytes directly to the stream, sharing the same lock as
	 * {@link #send(Frame)} so a caller writing out-of-band bytes (e.g.
	 * {@code SerialFrameTransport}'s LOW_POWER wake preamble, doc/PROTOCOL.md
	 * §17.1) can't interleave with a concurrent frame send. When a
	 * {@link TransferProgressListener} is set, splits the write into
	 * {@link #PROGRESS_CHUNK_SIZE} pieces so it gets periodic byte-count updates -
	 * purely client-side instrumentation, since these are byte streams (not
	 * datagram-truncating APIs), splitting one write into several sequential ones
	 * is indistinguishable on the wire from a single big write.
	 */
	protected final void sendRawBytes(byte[] data) throws IOException {
		OutputStream o = out;
		if (o == null) {
			throw new IOException("not connected");
		}
		TransferProgressListener progress = progressListener;
		synchronized (writeLock) {
			if (progress == null) {
				o.write(data);
			} else {
				int sent = 0;
				while (sent < data.length) {
					int len = Math.min(PROGRESS_CHUNK_SIZE, data.length - sent);
					o.write(data, sent, len);
					sent += len;
					progress.onProgress(sent, data.length);
				}
			}
			o.flush();
		}
	}

	@Override
	public final void setListener(FrameListener listener) {
		this.listener = listener;
	}

	@Override
	public final void setProgressListener(TransferProgressListener listener) {
		this.progressListener = listener;
	}

	@Override
	public final boolean isConnected() {
		return in != null;
	}

	/**
	 * Interrupts the reader thread and clears the stream references. Subclasses
	 * still own closing the underlying connection.
	 */
	protected final void stopReading() {
		Thread t = readerThread;
		if (t != null) {
			t.interrupt();
		}
		in = null;
		out = null;
	}
}
