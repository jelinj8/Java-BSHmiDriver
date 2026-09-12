package cz.bliksoft.hmieink.protocol;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import cz.bliksoft.javautils.ble.BleAdapter;
import cz.bliksoft.javautils.ble.BleException;
import cz.bliksoft.javautils.ble.BlePeripheral;

/**
 * BLE transport (doc/PROTOCOL.md §3.1/§19), built on the sibling
 * {@code BSToolbox-BLE} library (declared `provided` in this module's pom.xml,
 * doc/PROTOCOL.md §20 design note 17 - the consuming application must put it on
 * the runtime classpath to use this class).
 *
 * <p>
 * Takes a caller-owned {@link BleAdapter} rather than creating its own - each
 * {@code BleAdapter} spawns its own independent {@code ble-bridge} sidecar
 * process with its own peripheral cache, and a peripheral generally has to be
 * discovered via {@link BleAdapter#scan} on the <em>same</em> adapter before it
 * can be connected (confirmed on real hardware: connecting via a second,
 * freshly constructed {@code BleAdapter} that never scanned reliably fails with
 * "unknown peripheral address ... scan for it first", even for an address a
 * different adapter just found). This class doesn't scan or manage the
 * adapter's lifecycle - the caller does both and passes the address in.
 *
 * <p>
 * {@code BSToolbox-BLE} gives raw per-call {@code byte[]} write/notify with no
 * stream abstraction or auto-fragmentation - this class supplies that: incoming
 * notifications are pushed (from {@code BSToolbox-BLE}'s internal reader
 * thread, which must never block - see
 * {@link cz.bliksoft.javautils.ble.NotificationListener}) into a
 * {@link PipedOutputStream}, and {@link AbstractStreamFrameTransport}'s own
 * reader thread drains the connected {@link PipedInputStream} through the same
 * {@link FrameStreamReader} logic TCP/Serial use. Outgoing frames are
 * fragmented by {@link BleOutputStream}.
 */
public final class BleFrameTransport extends AbstractStreamFrameTransport {

	/**
	 * Sized well above any single stop-and-wait frame's chunk burst
	 * (doc/PROTOCOL.md's reliability model never has more than one logical frame in
	 * flight per direction), so {@code PipedOutputStream.write()} - called from
	 * BSToolbox-BLE's notification thread, which must not block - should never
	 * actually block waiting for this reader to drain it in practice.
	 */
	private static final int RX_PIPE_BUFFER_SIZE = 64 * 1024;

	private final BleAdapter adapter;
	private final String address;

	private volatile BlePeripheral peripheral;
	private volatile BleOutputStream txStream;

	/**
	 * @param adapter caller-owned; must have already discovered {@code address} via
	 *                {@link BleAdapter#scan} (see class doc). This class never
	 *                closes it.
	 * @param address the peripheral's BLE address, as reported by {@code adapter}'s
	 *                own scan.
	 */
	public BleFrameTransport(BleAdapter adapter, String address) {
		this.adapter = adapter;
		this.address = address;
	}

	@Override
	public void connect() throws IOException {
		try {
			BlePeripheral p = adapter.getPeripheral(address);
			p.connect();
			p.discoverServices();

			PipedOutputStream rxFeed = new PipedOutputStream();
			PipedInputStream rxIn = new PipedInputStream(rxFeed, RX_PIPE_BUFFER_SIZE);

			p.subscribe(Ble.SERVICE_UUID, Ble.CHARACTERISTIC_UUID, (charUuid, value) -> {
				try {
					rxFeed.write(value);
				} catch (IOException e) {
					// Pipe closed or (per the sizing note above, only in a pathological case) full
					// -
					// drop the bytes. NotificationListener must not block or throw further.
				}
			});

			BleOutputStream out = new BleOutputStream(p, Ble.SERVICE_UUID, Ble.CHARACTERISTIC_UUID,
					Ble.DEFAULT_MAX_CHUNK_SIZE);
			txStream = out;
			peripheral = p;

			beginReading(rxIn, out, "crowpanel-ble-reader-" + address);
		} catch (BleException e) {
			throw new IOException("BLE connect failed for " + address, e);
		}
	}

	/**
	 * Sets the max single-write size once the real value is known from the
	 * handshake's MAX_CHUNK_SIZE capability (doc/PROTOCOL.md §5.2) - not yet known
	 * at {@link #connect()} time, since parsing the handshake TLV payload is a
	 * layer above this transport. Until called, a conservative default
	 * ({@link Ble#DEFAULT_MAX_CHUNK_SIZE}) is used. {@code maxChunkSize <= 0} ("no
	 * limit reported") is ignored, per {@link FrameTransport#setMaxChunkSize}'s
	 * contract.
	 */
	@Override
	public void setMaxChunkSize(int maxChunkSize) {
		if (maxChunkSize <= 0) {
			return;
		}
		BleOutputStream out = txStream;
		if (out != null) {
			out.setMaxChunkSize(maxChunkSize);
		}
	}

	/**
	 * Disconnects the peripheral. Does NOT close the caller-owned
	 * {@link BleAdapter} passed to the constructor.
	 */
	@Override
	public void close() throws IOException {
		stopReading();
		BlePeripheral p = peripheral;
		try {
			if (p != null) {
				p.disconnect();
			}
		} catch (BleException e) {
			// best effort - closing anyway
		}
	}
}
