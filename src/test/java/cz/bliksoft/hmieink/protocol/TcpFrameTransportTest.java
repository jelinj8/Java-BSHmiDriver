package cz.bliksoft.hmieink.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Exercises {@link TcpFrameTransport} end-to-end against a real loopback TCP server standing in
 * for the device - no hardware needed. Also stands in as the main test of
 * {@link AbstractStreamFrameTransport}'s reader-thread/listener plumbing, which
 * {@link SerialFrameTransport} shares.
 */
class TcpFrameTransportTest {

	@Test
	void roundTripsFramesBothDirections() throws Exception {
		try (FakeDeviceServer server = FakeDeviceServer.start()) {
			TcpFrameTransport client = new TcpFrameTransport("127.0.0.1", server.port());
			BlockingQueue<Frame> received = new ArrayBlockingQueue<>(10);
			client.setListener(new FrameListener() {
				@Override
				public void onFrame(Frame frame) {
					received.add(frame);
				}
			});

			client.connect();
			try {
				client.send(new Frame(CommandId.HANDSHAKE_REQUEST, 0, null));
				byte[] fromDevice = server.awaitFrameBytes();
				Frame decoded = Frame.decode(fromDevice);
				assertEquals(CommandId.HANDSHAKE_REQUEST, decoded.getCommandId());

				server.reply(new Frame(CommandId.HANDSHAKE_RESPONSE, 0, "ok".getBytes(StandardCharsets.UTF_8)));
				Frame response = received.poll(5, TimeUnit.SECONDS);
				assertEquals(CommandId.HANDSHAKE_RESPONSE, response.getCommandId());
				assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), response.getPayload());
			} finally {
				client.close();
			}
		}
	}

	@Test
	void deliversMultipleFramesSentBackToBack() throws Exception {
		try (FakeDeviceServer server = FakeDeviceServer.start()) {
			TcpFrameTransport client = new TcpFrameTransport("127.0.0.1", server.port());
			BlockingQueue<Frame> received = new ArrayBlockingQueue<>(10);
			client.setListener(frame -> received.add(frame));
			client.connect();
			try {
				server.replyRaw(new Frame(CommandId.BUTTON_EVENT, 1, new byte[] {1}).encode());
				server.replyRaw(new Frame(CommandId.BUTTON_EVENT, 2, new byte[] {2}).encode());
				server.replyRaw(new Frame(CommandId.BUTTON_EVENT, 3, new byte[] {3}).encode());

				for (int expectedSeq = 1; expectedSeq <= 3; expectedSeq++) {
					Frame f = received.poll(5, TimeUnit.SECONDS);
					if (f == null) {
						fail("did not receive frame seq=" + expectedSeq + " in time");
					}
					assertEquals(expectedSeq, f.getSeq());
				}
			} finally {
				client.close();
			}
		}
	}

	@Test
	void onTransportClosedFiresWhenServerDisconnects() throws Exception {
		FakeDeviceServer server = FakeDeviceServer.start();
		TcpFrameTransport client = new TcpFrameTransport("127.0.0.1", server.port());
		CountDownLatch closedLatch = new CountDownLatch(1);
		client.setListener(new FrameListener() {
			@Override
			public void onFrame(Frame frame) {
			}

			@Override
			public void onTransportClosed(IOException cause) {
				closedLatch.countDown();
			}
		});

		client.connect();
		server.close();
		assertTrue(closedLatch.await(5, TimeUnit.SECONDS), "onTransportClosed was not called");
		client.close();
	}

	/** A minimal single-connection TCP server standing in for the device side, for tests only. */
	private static final class FakeDeviceServer implements AutoCloseable {
		private final ServerSocket serverSocket;
		private volatile Socket accepted;
		private volatile InputStream in;
		private volatile OutputStream out;
		private final CountDownLatch acceptedLatch = new CountDownLatch(1);

		private FakeDeviceServer(ServerSocket serverSocket) {
			this.serverSocket = serverSocket;
		}

		static FakeDeviceServer start() throws IOException {
			ServerSocket ss = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
			FakeDeviceServer server = new FakeDeviceServer(ss);
			Thread t = new Thread(server::acceptLoop, "fake-device-server");
			t.setDaemon(true);
			t.start();
			return server;
		}

		private void acceptLoop() {
			try {
				accepted = serverSocket.accept();
				in = accepted.getInputStream();
				out = accepted.getOutputStream();
			} catch (IOException ignored) {
				// server closed while waiting for the (single) connection - fine for these tests
			} finally {
				acceptedLatch.countDown();
			}
		}

		int port() {
			return serverSocket.getLocalPort();
		}

		byte[] awaitFrameBytes() throws IOException, InterruptedException {
			acceptedLatch.await(5, TimeUnit.SECONDS);
			FrameStreamReader reader = new FrameStreamReader(in);
			return reader.readFrame().encode();
		}

		void reply(Frame frame) throws IOException {
			replyRaw(frame.encode());
		}

		void replyRaw(byte[] wireBytes) throws IOException {
			out.write(wireBytes);
			out.flush();
		}

		@Override
		public void close() throws IOException {
			serverSocket.close();
			Socket s = accepted;
			if (s != null) {
				s.close();
			}
		}
	}
}
