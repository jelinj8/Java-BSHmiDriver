package cz.bliksoft.hmieink.protocol.manual;

import java.util.concurrent.atomic.AtomicReference;

import cz.bliksoft.hmieink.protocol.Ble;
import cz.bliksoft.hmieink.protocol.BleHmiDevice;
import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.javautils.ble.BleAdapter;
import cz.bliksoft.javautils.ble.ScanFilter;

/**
 * Manual, real-hardware verification that the local drawing primitives
 * (doc/PROTOCOL.md §12.2-§12.5) work over the BLE transport specifically - the
 * BLE counterpart of {@link DrawPrimitivesManualCheck}, which only ever
 * exercised Serial. Not part of the automated {@code mvn test} suite (real
 * device required). Scans for a device advertising the CrowPanel service UUID,
 * connects via {@link BleHmiDevice}, then sends the exact same deferred
 * rect/rect(XOR)/circle/circle/line sequence followed by one {@code REFRESH} as
 * {@link DrawPrimitivesManualCheck} does, so a passing run is directly
 * comparable to the already-verified Serial result:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;BSToolbox-BLE jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.DrawPrimitivesBleManualCheck [address-or-name-substring]
 * </pre>
 *
 * <p>
 * With no argument, connects to the first device found advertising the
 * CrowPanel service UUID - fine with exactly one such device in range. With
 * multiple CrowPanel units powered on at once, pass a substring of the target's
 * address (e.g. {@code A5:A3:65}) or advertised name (e.g. {@code A5A364}) to
 * pick a specific one instead of racing the scan order.
 *
 * <p>
 * Uses the <em>same</em> {@link BleAdapter} instance for both the scan and the
 * {@link BleHmiDevice} it hands the discovered address to - see
 * {@link cz.bliksoft.hmieink.protocol.BleFrameTransport}'s class doc for why
 * that's required.
 */
public final class DrawPrimitivesBleManualCheck {

	private static final long SCAN_TIMEOUT_MS = 8000;

	private DrawPrimitivesBleManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		String target = args.length > 0 ? args[0].toUpperCase(java.util.Locale.ROOT) : null;
		AtomicReference<String> addressRef = new AtomicReference<>();
		AtomicReference<String> nameRef = new AtomicReference<>();

		try (BleAdapter adapter = new BleAdapter()) {
			System.out.println(
					"Scanning for service " + Ble.SERVICE_UUID + (target != null ? " matching \"" + target + "\"" : "")
							+ " (up to " + SCAN_TIMEOUT_MS + " ms)...");
			adapter.scan(new ScanFilter().withServiceUuid(Ble.SERVICE_UUID), SCAN_TIMEOUT_MS, (address, name, rssi) -> {
				System.out.println("Found: " + address + " name=" + name + " rssi=" + rssi);
				boolean matches = target == null || address.toUpperCase(java.util.Locale.ROOT).contains(target)
						|| (name != null && name.toUpperCase(java.util.Locale.ROOT).contains(target));
				if (matches && addressRef.compareAndSet(null, address)) {
					nameRef.set(name);
				}
			});

			String address = addressRef.get();
			if (address == null) {
				System.err.println("FAILED: no device advertising service " + Ble.SERVICE_UUID
						+ (target != null ? " matching \"" + target + "\"" : "") + " found within " + SCAN_TIMEOUT_MS
						+ " ms");
				System.exit(1);
				return;
			}

			System.out.println("Connecting to " + address + " (" + nameRef.get() + ")...");
			// Same `adapter` instance that ran the scan above - see BleFrameTransport's
			// class doc.
			BleHmiDevice device = new BleHmiDevice(adapter, address);
			device.connect();
			System.out.println("Connected.");
			try {
				System.out.println("-> DRAW_RECT filled black (20,20,100,60), REPLACE, deferred");
				device.drawRect(20, 20, 100, 60, Color.BLACK, DrawMode.REPLACE, true, 1, 0);

				System.out
						.println("-> DRAW_RECT filled black (60,40,80,60), XOR, deferred - overlap with the first rect "
								+ "should turn white");
				device.drawRect(60, 40, 80, 60, Color.BLACK, DrawMode.XOR, true, 1, 0);

				System.out.println("-> DRAW_CIRCLE outline black, center (300,80) r=50, width=4, deferred");
				device.drawCircle(300, 80, 50, Color.BLACK, DrawMode.REPLACE, false, 4, 0);

				System.out.println("-> DRAW_CIRCLE filled black, center (300,200) r=40, deferred");
				device.drawCircle(300, 200, 40, Color.BLACK, DrawMode.REPLACE, true, 1, 0);

				System.out.println("-> DRAW_LINE thick black (20,150)-(150,280), width=5, deferred");
				device.drawLine(20, 150, 150, 280, Color.BLACK, DrawMode.REPLACE, 5, 0);

				System.out.println("-> sending REFRESH(MODE=0x01) - everything above should appear together now");
				device.refresh(0x01); // MODE=0x01: force full-panel refresh (doc/PROTOCOL.md §12.8)
				System.out.println("OK: REFRESH ACKed - check the panel: a black rect with a white XOR-cutout "
						+ "square, an outline circle, a filled circle, and a thick diagonal line");
			} catch (CommandNackException e) {
				System.err.println("FAILED: NACK status=0x" + Integer.toHexString(e.getStatus()) + " for commandId=0x"
						+ Integer.toHexString(e.getRefCommandId()));
				System.exit(1);
			} finally {
				device.close();
			}
		}
	}
}
