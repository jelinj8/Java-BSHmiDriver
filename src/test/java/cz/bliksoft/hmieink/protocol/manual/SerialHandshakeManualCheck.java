package cz.bliksoft.hmieink.protocol.manual;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.HandshakeCapabilities;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;

/**
 * Manual, real-hardware verification for {@link SerialFrameTransport} and {@link CommandClient}:
 * sends HANDSHAKE_REQUEST over a real serial port via the stop-and-wait command client and decodes
 * the response through {@link HandshakeCapabilities}. NOT part of the automated `mvn test` suite
 * (this class's name doesn't match Surefire's default *Test.java / Test*.java patterns, and it
 * requires a real board attached) - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.SerialHandshakeManualCheck COM5
 * </pre>
 */
public final class SerialHandshakeManualCheck {

	private SerialHandshakeManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: SerialHandshakeManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud...");
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
