package cz.bliksoft.hmieink.protocol.manual;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.HandshakeCapabilities;
import cz.bliksoft.hmieink.protocol.TcpFrameTransport;

/**
 * Manual, real-hardware verification for {@link TcpFrameTransport} - the WiFi/TCP counterpart of
 * {@link SerialHandshakeManualCheck}. NOT part of the automated `mvn test` suite (real device
 * required, and its IP address isn't known ahead of time). Run it once the board's boot log
 * (Serial monitor) has printed "TCP: server listening on &lt;ip&gt;:&lt;port&gt;":
 *
 * <pre>
 * java -cp target/classes;target/test-classes cz.bliksoft.hmieink.protocol.manual.TcpHandshakeManualCheck &lt;ip&gt; [port]
 * </pre>
 */
public final class TcpHandshakeManualCheck {

	private static final int DEFAULT_PORT = 5577; // firmware/src/main.cpp kTcpPort, doc/PROTOCOL.md §3.2

	private TcpHandshakeManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 1 || args.length > 2) {
			System.err.println("usage: TcpHandshakeManualCheck <ip> [port, default " + DEFAULT_PORT + "]");
			System.exit(2);
		}
		String host = args[0];
		int port = args.length == 2 ? Integer.parseInt(args[1]) : DEFAULT_PORT;

		CommandClient client = new CommandClient(new TcpFrameTransport(host, port));
		System.out.println("Connecting to " + host + ":" + port + "...");
		client.connect();
		try {
			System.out.println("-> sending HANDSHAKE_REQUEST");
			// PIN_TYPE=NONE, PIN_LEN=0 (doc/PROTOCOL.md §5.3) - no pin offered.
			Frame response = client.send(CommandId.HANDSHAKE_REQUEST, new byte[] { 0, 0 });
			System.out.println(
					"OK: got HANDSHAKE_RESPONSE with " + response.getPayload().length + "-byte TLV payload");

			HandshakeCapabilities caps = HandshakeCapabilities.parse(response.getPayload());
			System.out.printf("  protocolVersion=%d%n", caps.getProtocolVersion());
			System.out.printf("  display=%dx%d colorDepth=%d%n", caps.getDisplayWidthPx(), caps.getDisplayHeightPx(),
					caps.getColorDepth());
			System.out.printf("  pixelPitch=%d/%dum -> dpi=%.1f/%.1f%n", caps.getPixelPitchXUm(),
					caps.getPixelPitchYUm(), caps.getDpiX(), caps.getDpiY());
			System.out.printf("  maxChunkSize=%d featureBitmask=0x%08X%n", caps.getMaxChunkSize(),
					caps.getFeatureBitmask());
			System.out.printf("  deviceModel=%s firmwareVersion=%s%n", caps.getDeviceModel(), caps.getFirmwareVersion());
			System.out.printf("  deviceName=%s activeTransport=0x%02X%n", caps.getDeviceName(),
					caps.getActiveTransport());
		} finally {
			client.close();
		}
	}
}
