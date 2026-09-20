package cz.bliksoft.hmieink.protocol;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import cz.bliksoft.javautils.ble.BleAdapter;
import cz.bliksoft.javautils.ble.BleException;
import cz.bliksoft.javautils.ble.BlePeripheral;
import cz.bliksoft.javautils.ble.ScanFilter;
import cz.bliksoft.javautils.ble.utils.BleUtils;

/**
 * BLE transport (doc/PROTOCOL.md §3.1/§19), built on the sibling
 * {@code BSToolbox-BLE} library (declared `provided` in this module's pom.xml,
 * doc/PROTOCOL.md §20 design note 17 - the consuming application must put it on
 * the runtime classpath to use this class).
 *
 * <p>
 * Takes a caller-owned {@link BleAdapter} rather than creating its own for the
 * <em>initial</em> connection - each {@code BleAdapter} spawns its own
 * independent {@code ble-bridge} sidecar process with its own peripheral cache,
 * and a peripheral generally has to be discovered via {@link BleAdapter#scan}
 * on the <em>same</em> adapter before it can be connected (confirmed on real
 * hardware: connecting via a second, freshly constructed {@code BleAdapter}
 * that never scanned reliably fails with "unknown peripheral address ... scan
 * for it first", even for an address a different adapter just found).
 * {@link #reestablishAfterDeviceReboot()} is the one exception to "the caller
 * owns the adapter" - see its own doc: it discards the current adapter (closing
 * it, whether caller- or self-supplied) and replaces it with a fresh one
 * internally, since that's the only thing that's actually proven reliable for
 * reconnecting to a peripheral this same sidecar process already had connected
 * once and then lost.
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
 *
 * <p>
 * Programmatic pairing (no OS pairing prompt) is available but opt-in - unlike
 * MeshCore radios, this HMI device family has no established default BLE PIN,
 * and the reference CrowPanel board doesn't require bonding at all (an
 * unencrypted GATT service). Pass a PIN to
 * {@link #BleFrameTransport(BleAdapter, String, String)} only for a board/
 * firmware build that does require it; {@code null}/blank (the plain two-arg
 * constructor) skips pairing entirely, matching this class's original
 * behavior. See {@code cz.bliksoft.java:common-java-utils-ble}'s own
 * "Programmatic pairing" feature (Windows and Linux; not possible on macOS,
 * a CoreBluetooth limitation, not this library's). A supplied PIN is only ever
 * used once, on the very first {@link #connect()} - not on a later
 * {@link #reestablishAfterDeviceReboot()} - since a previously-paired device
 * stays bonded at the OS level across a mere device reboot.
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

	/**
	 * Bound on the re-scan {@link #reestablishAfterDeviceReboot()} does on the
	 * fresh adapter before each reconnect attempt - see that method's doc.
	 */
	private static final long RECONNECT_SCAN_TIMEOUT_MS = 10_000;

	private volatile BleAdapter adapter;
	private volatile boolean ownsAdapter;
	private final String address;

	private volatile BlePeripheral peripheral;
	private volatile BleOutputStream txStream;

	// Consumed (nulled) the first time connect() succeeds - see class doc and
	// the BleAdapter,String,String constructor.
	private volatile String pairPin;

	/**
	 * Equivalent to {@link #BleFrameTransport(BleAdapter, String, String)} with a
	 * {@code null} PIN - never attempts pairing. This is the right choice for a
	 * device known not to require BLE bonding (e.g. the reference CrowPanel
	 * board).
	 *
	 * @param adapter caller-owned; must have already discovered {@code address} via
	 *                {@link BleAdapter#scan} (see class doc). Not closed by this
	 *                class unless a later {@link #reestablishAfterDeviceReboot()}
	 *                supersedes it with a fresh, self-owned one.
	 * @param address the peripheral's BLE address, as reported by {@code adapter}'s
	 *                own scan.
	 */
	public BleFrameTransport(BleAdapter adapter, String address) {
		this(adapter, address, null);
	}

	/**
	 * @param adapter caller-owned; must have already discovered {@code address} via
	 *                {@link BleAdapter#scan} (see class doc). Not closed by this
	 *                class unless a later {@link #reestablishAfterDeviceReboot()}
	 *                supersedes it with a fresh, self-owned one.
	 * @param address the peripheral's BLE address, as reported by {@code adapter}'s
	 *                own scan.
	 * @param pairPin PIN to pair with automatically right after connecting (see
	 *                class doc), or {@code null}/blank to not attempt pairing at
	 *                all.
	 */
	public BleFrameTransport(BleAdapter adapter, String address, String pairPin) {
		this.adapter = adapter;
		this.address = address;
		this.pairPin = pairPin;
	}

	@Override
	public void connect() throws IOException {
		String pinToUse = pairPin;
		pairPin = null;
		try {
			BlePeripheral p = adapter.getPeripheral(address);
			p.connect();
			if (pinToUse != null && !pinToUse.trim().isEmpty()) {
				// Best-effort, not fatal: an already-paired device (possible if the caller
				// re-supplies a PIN, or the user paired manually before) typically errors
				// here rather than silently succeeding again - that's expected, not a real
				// failure. If the device genuinely still needs bonding and this didn't
				// establish it, discoverServices()/subscribe() below fail instead.
				try {
					p.pair(pinToUse);
				} catch (BleException e) {
					// Logged at the call site's discretion - this class has no logger of its
					// own (see AbstractStreamFrameTransport); swallow and continue, matching
					// the "opt-in, best-effort" contract in the class doc.
				}
			}
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
	 * Disconnects the peripheral. Closes the adapter too, but only if a previous
	 * {@link #reestablishAfterDeviceReboot()} made this class own it - the
	 * originally caller-supplied adapter is never closed here.
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
		if (ownsAdapter) {
			adapter.close();
		}
	}

	/**
	 * Discards the current adapter and connects via a brand new one - plain
	 * {@link #close()}+{@link #connect()} on the same adapter (the default this
	 * overrides), and even a bounded re-scan on that same adapter first, both
	 * proved unreliable on real hardware for reconnecting to a peripheral this
	 * adapter's {@code ble-bridge} sidecar process already had connected once and
	 * then lost (e.g. the device rebooted, doc/PROTOCOL.md §16): a fresh scan would
	 * time out completely on the original adapter, while a brand new process's
	 * fresh scan found and connected to the same address immediately, every time -
	 * some sidecar-process-local state evidently doesn't recover on its own after a
	 * peripheral disconnects and comes back. Recreating the adapter is the one
	 * thing that's actually worked.
	 *
	 * <p>
	 * The new adapter is scanned (bounded, matching {@code address} - same
	 * fast-stop mechanics {@code Cli}'s own {@code =<address>} selector uses)
	 * before use, since a fresh adapter has no history of {@code address} at all.
	 * On success, the old adapter is closed (whether it was the original
	 * caller-supplied one or an earlier replacement) and this class now owns the
	 * new one, closing it in turn from {@link #close()}. On failure, the old
	 * adapter is left untouched and the new one is closed instead, so a failed
	 * reconnect attempt doesn't strand two live sidecar processes.
	 */
	@Override
	public void reestablishAfterDeviceReboot() throws IOException {
		close();
		BleAdapter fresh = null;
		try {
			fresh = new BleAdapter();
			BleUtils.scan(fresh, new ScanFilter().withServiceUuid(Ble.SERVICE_UUID).withAddress(address),
					RECONNECT_SCAN_TIMEOUT_MS);
		} catch (BleException e) {
			if (fresh != null) {
				fresh.close();
			}
			throw new IOException("BLE re-scan failed for " + address, e);
		}
		BleAdapter old = adapter;
		adapter = fresh;
		ownsAdapter = true;
		if (old != null) {
			old.close();
		}
		connect();
	}
}
