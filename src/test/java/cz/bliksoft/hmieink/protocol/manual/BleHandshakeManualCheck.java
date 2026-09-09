package cz.bliksoft.hmieink.protocol.manual;

import java.util.concurrent.atomic.AtomicReference;

import cz.bliksoft.hmieink.protocol.Ble;
import cz.bliksoft.hmieink.protocol.BleFrameTransport;
import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.HandshakeCapabilities;
import cz.bliksoft.javautils.ble.BleAdapter;
import cz.bliksoft.javautils.ble.ScanFilter;

/**
 * Manual, real-hardware verification for {@link BleFrameTransport} - the BLE counterpart of
 * {@link SerialHandshakeManualCheck}/{@link TcpHandshakeManualCheck}. NOT part of the automated
 * `mvn test` suite (real device required). Scans for a device advertising the CrowPanel service
 * UUID (doc/PROTOCOL.md §19), connects, and exchanges HANDSHAKE_REQUEST/HANDSHAKE_RESPONSE:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jSerialComm jar&gt;;&lt;BSToolbox-BLE jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.BleHandshakeManualCheck
 * </pre>
 *
 * <p>
 * Uses the <em>same</em> {@link BleAdapter} instance for both the scan and the
 * {@link BleFrameTransport} it hands the discovered address to - see {@link BleFrameTransport}'s
 * class doc for why that's required (a second, freshly constructed adapter that never scanned
 * reliably fails to connect to an address a different adapter just discovered).
 */
public final class BleHandshakeManualCheck {

	private static final long SCAN_TIMEOUT_MS = 8000;

	private BleHandshakeManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		AtomicReference<String> addressRef = new AtomicReference<>();
		AtomicReference<String> nameRef = new AtomicReference<>();

		try (BleAdapter adapter = new BleAdapter()) {
			System.out.println("Scanning for service " + Ble.SERVICE_UUID + " (up to " + SCAN_TIMEOUT_MS + " ms)...");
			adapter.scan(new ScanFilter().withServiceUuid(Ble.SERVICE_UUID), SCAN_TIMEOUT_MS, (address, name, rssi) -> {
				if (addressRef.compareAndSet(null, address)) {
					nameRef.set(name);
					System.out.println("Found: " + address + " name=" + name + " rssi=" + rssi);
				}
			});

			String address = addressRef.get();
			if (address == null) {
				System.err.println("FAILED: no device advertising service " + Ble.SERVICE_UUID + " found within "
						+ SCAN_TIMEOUT_MS + " ms");
				System.exit(1);
				return;
			}

			System.out.println("Connecting to " + address + " (" + nameRef.get() + ")...");
			// Same `adapter` instance that ran the scan above - see BleFrameTransport's class doc.
			CommandClient client = new CommandClient(new BleFrameTransport(adapter, address));
			client.connect();
			System.out.println("Connected.");
			try {
				System.out.println("-> sending HANDSHAKE_REQUEST");
				// PIN_TYPE=NONE, PIN_LEN=0 (doc/PROTOCOL.md §5.3) - no pin offered.
				Frame response = client.send(CommandId.HANDSHAKE_REQUEST, new byte[] { 0, 0 }, 10_000);
				System.out.println(
						"OK: got HANDSHAKE_RESPONSE with " + response.getPayload().length + "-byte TLV payload");

				HandshakeCapabilities caps = HandshakeCapabilities.parse(response.getPayload());
				System.out.printf("  protocolVersion=%d%n", caps.getProtocolVersion());
				System.out.printf("  display=%dx%d colorDepth=%d%n", caps.getDisplayWidthPx(),
						caps.getDisplayHeightPx(), caps.getColorDepth());
				System.out.printf("  pixelPitch=%d/%dum -> dpi=%.1f/%.1f%n", caps.getPixelPitchXUm(),
						caps.getPixelPitchYUm(), caps.getDpiX(), caps.getDpiY());
				System.out.printf("  maxChunkSize=%d featureBitmask=0x%08X%n", caps.getMaxChunkSize(),
						caps.getFeatureBitmask());
				System.out.printf("  deviceModel=%s firmwareVersion=%s%n", caps.getDeviceModel(),
						caps.getFirmwareVersion());
				System.out.printf("  deviceName=%s activeTransport=0x%02X%n", caps.getDeviceName(),
						caps.getActiveTransport());
			} finally {
				client.close();
			}
		}
	}
}
