package cz.bliksoft.hmieink.protocol;

/**
 * {@link HmiDevice} over {@link TcpFrameTransport} (doc/PROTOCOL.md §3.2). No
 * {@code provided} dependency.
 */
public final class TcpHmiDevice extends HmiDevice {

	public TcpHmiDevice(String host, int port) {
		super(new TcpFrameTransport(host, port));
	}

	public TcpHmiDevice(String host, int port, int connectTimeoutMs) {
		super(new TcpFrameTransport(host, port, connectTimeoutMs));
	}
}
