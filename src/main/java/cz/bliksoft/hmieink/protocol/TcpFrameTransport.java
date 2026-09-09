package cz.bliksoft.hmieink.protocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** WiFi/TCP transport (doc/PROTOCOL.md §3.2): the device runs a TCP server, this connects as a client. */
public final class TcpFrameTransport extends AbstractStreamFrameTransport {

	private final String host;
	private final int port;
	private final int connectTimeoutMs;

	private volatile Socket socket;

	public TcpFrameTransport(String host, int port) {
		this(host, port, 5000);
	}

	public TcpFrameTransport(String host, int port, int connectTimeoutMs) {
		this.host = host;
		this.port = port;
		this.connectTimeoutMs = connectTimeoutMs;
	}

	@Override
	public void connect() throws IOException {
		Socket s = new Socket();
		s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
		s.setTcpNoDelay(true);
		socket = s;
		beginReading(s.getInputStream(), s.getOutputStream(), "crowpanel-tcp-reader-" + host + ":" + port);
	}

	@Override
	public void close() throws IOException {
		stopReading();
		Socket s = socket;
		if (s != null) {
			s.close();
		}
	}
}
