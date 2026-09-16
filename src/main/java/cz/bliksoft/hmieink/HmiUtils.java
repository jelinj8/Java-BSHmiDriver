package cz.bliksoft.hmieink;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import cz.bliksoft.hmieink.protocol.BleHmiDevice;
import cz.bliksoft.hmieink.protocol.FileHmiDevice;
import cz.bliksoft.hmieink.protocol.HandshakeCapabilities;
import cz.bliksoft.hmieink.protocol.HmiDevice;
import cz.bliksoft.hmieink.protocol.SerialHmiDevice;
import cz.bliksoft.hmieink.protocol.TcpHmiDevice;
import cz.bliksoft.javautils.ble.BleAdapter;
import cz.bliksoft.javautils.ble.BleException;
import cz.bliksoft.javautils.ble.BleSidecarException;
import cz.bliksoft.javautils.ble.ScanFilter;
import cz.bliksoft.javautils.ble.utils.BleUtils;
import cz.bliksoft.javautils.ble.utils.BleUtils.BleDeviceResult;

/**
 * Integration entry point collapsing the connect+handshake boilerplate that
 * {@link Cli} and this project's manual hardware-check tools used to each
 * re-derive by hand: constructing the right {@link HmiDevice} subclass for a
 * transport, connecting, and handshaking right after (doc/PROTOCOL.md §5.3 -
 * without a real HANDSHAKE_REQUEST, any PIN offered has no effect at all).
 *
 * <p>
 * Deliberately split into one nested class per transport ({@link Serial},
 * {@link Tcp}, {@link File}, {@link Ble}), mirroring the same isolation
 * {@link HmiDevice}'s own transport-specific subclasses already rely on: a
 * nested static class compiles to its own independent {@code .class} file, so
 * calling e.g. {@link Tcp#connect} - which never mentions BLE/Serial types -
 * never forces the JVM to resolve {@code jSerialComm}/BSToolbox-BLE at all. A
 * single flat class with one method per transport could not offer that
 * guarantee: JVM verification resolves every type referenced by any method of a
 * class as soon as that class is loaded, not lazily per call.
 */
public final class HmiUtils {

	private HmiUtils() {
	}

	/**
	 * Handshakes right after connecting - offering {@code adminPin} if given, else
	 * {@code usagePin} if given, else no PIN at all. Mirrors
	 * {@link HmiDevice#handshake(String, String)}, which already implements this
	 * selection; kept here too so every {@code connectAndHandshake} overload below
	 * can share one place that calls it.
	 */
	public static HandshakeCapabilities handshake(HmiDevice device, String adminPin, String usagePin)
			throws IOException {
		return device.handshake(adminPin, usagePin);
	}

	public static final class Serial {
		private Serial() {
		}

		public static SerialHmiDevice connect(String port) throws IOException {
			SerialHmiDevice device = new SerialHmiDevice(port);
			device.connect();
			return device;
		}

		public static SerialHmiDevice connect(String port, int baudRate) throws IOException {
			SerialHmiDevice device = new SerialHmiDevice(port, baudRate);
			device.connect();
			return device;
		}

		public static SerialHmiDevice connectAndHandshake(String port, String adminPin, String usagePin)
				throws IOException {
			SerialHmiDevice device = connect(port);
			handshake(device, adminPin, usagePin);
			return device;
		}
	}

	public static final class Tcp {
		private Tcp() {
		}

		public static TcpHmiDevice connect(String host, int port) throws IOException {
			TcpHmiDevice device = new TcpHmiDevice(host, port);
			device.connect();
			return device;
		}

		public static TcpHmiDevice connectAndHandshake(String host, int port, String adminPin, String usagePin)
				throws IOException {
			TcpHmiDevice device = connect(host, port);
			handshake(device, adminPin, usagePin);
			return device;
		}
	}

	public static final class File {
		private File() {
		}

		public static FileHmiDevice connect(String path) throws IOException {
			FileHmiDevice device = new FileHmiDevice(path);
			device.connect();
			return device;
		}

		public static FileHmiDevice connectAndHandshake(String path, String adminPin, String usagePin)
				throws IOException {
			FileHmiDevice device = connect(path);
			handshake(device, adminPin, usagePin);
			return device;
		}
	}

	public static final class Ble {

		public static final long DEFAULT_SCAN_TIMEOUT_MS = 8000;

		private Ble() {
		}

		public static BleAdapter newAdapter() throws BleSidecarException {
			return new BleAdapter();
		}

		public static List<BleDeviceResult> scan(BleAdapter adapter, long timeoutMs) throws BleException {
			return BleUtils.scan(adapter,
					new ScanFilter().withServiceUuid(cz.bliksoft.hmieink.protocol.Ble.SERVICE_UUID), timeoutMs);
		}

		public static List<BleDeviceResult> find(BleAdapter adapter, String nameOrAddress, long timeoutMs)
				throws BleException {
			return BleUtils.find(adapter,
					new ScanFilter().withServiceUuid(cz.bliksoft.hmieink.protocol.Ble.SERVICE_UUID), nameOrAddress,
					timeoutMs);
		}

		/**
		 * Scans for a device whose address or name is an <em>exact</em>
		 * (case-insensitive) match for {@code addressOrName}, stopping as soon as it's
		 * found instead of always waiting out {@code timeoutMs} - see
		 * {@link ScanFilter#withAddress}/{@link ScanFilter#withName}. Unlike the old
		 * {@code BleUtils.scan(..., match)} this replaces, {@link BleAdapter} itself
		 * guarantees only a genuine exact match is ever returned, so this can no longer
		 * fall back to unrelated devices on a timeout the way {@link #resolveExact}'s
		 * doc describes.
		 */
		public static List<BleDeviceResult> scanExact(BleAdapter adapter, String addressOrName, long timeoutMs)
				throws BleException {
			return BleUtils.scan(adapter,
					new ScanFilter().withServiceUuid(cz.bliksoft.hmieink.protocol.Ble.SERVICE_UUID)
							.withAddress(addressOrName).withName(addressOrName),
					timeoutMs);
		}

		/**
		 * Resolve a device selector to exactly one device from a BLE scan.
		 *
		 * <p>
		 * Supported selectors:
		 * <ul>
		 * <li>{@code "*"} - return the first device found</li>
		 * <li>{@code "1"} - return the only device; throw if zero or multiple
		 * found</li>
		 * <li>{@code "<substring>"} - match against device name or address
		 * (case-insensitive); throw if zero or multiple matches</li>
		 * <li>{@code "<name1>,<name2>,..."} - comma-separated list of substrings to
		 * match (each is tried until a unique match is found)</li>
		 * </ul>
		 *
		 * @param found         the list of devices found during scan
		 * @param selector      the selector string (e.g. "*", "1", "device name",
		 *                      "address", or comma-separated substrings)
		 * @param scanTimeoutMs the scan timeout used to produce {@code found}, quoted
		 *                      back in any thrown message only
		 * @return the resolved device result
		 * @throws IOException if no match, ambiguous match, or other resolution error
		 */
		public static BleDeviceResult resolveDevice(List<BleDeviceResult> found, String selector, long scanTimeoutMs)
				throws IOException {
			if ("*".equals(selector)) {
				if (found.isEmpty()) {
					throw new IOException("no devices found advertising service "
							+ cz.bliksoft.hmieink.protocol.Ble.SERVICE_UUID + " within " + scanTimeoutMs + "ms");
				}
				return found.get(0);
			} else if ("1".equals(selector)) {
				if (found.size() == 0) {
					throw new IOException("no devices found advertising service "
							+ cz.bliksoft.hmieink.protocol.Ble.SERVICE_UUID + " within " + scanTimeoutMs + "ms");
				} else if (found.size() > 1) {
					List<String> addresses = new ArrayList<>();
					for (BleDeviceResult d : found) {
						addresses.add(d.getAddress());
					}
					throw new IOException("multiple devices found advertising service "
							+ cz.bliksoft.hmieink.protocol.Ble.SERVICE_UUID + " within " + scanTimeoutMs + "ms: "
							+ addresses);
				}
				return found.get(0);
			} else {
				List<BleDeviceResult> matches = new ArrayList<>();
				String lowerSelector = selector.toLowerCase(Locale.ROOT);
				for (BleDeviceResult device : found) {
					if (device.getAddress().toLowerCase(Locale.ROOT).contains(lowerSelector)
							|| (device.getName() != null
									&& device.getName().toLowerCase(Locale.ROOT).contains(lowerSelector))) {
						matches.add(device);
					}
				}
				if (matches.isEmpty()) {
					throw new IOException("no device matching \"" + selector + "\" found advertising service "
							+ cz.bliksoft.hmieink.protocol.Ble.SERVICE_UUID + " within " + scanTimeoutMs + "ms");
				} else if (matches.size() > 1) {
					List<String> addresses = new ArrayList<>();
					for (BleDeviceResult d : matches) {
						addresses.add(d.getAddress());
					}
					throw new IOException("multiple devices matched \"" + selector + "\": " + addresses);
				}
				return matches.get(0);
			}
		}

		/**
		 * Narrows a scan result down to every device that is an exact address/name
		 * match for {@code selector} - unlike {@link #resolveDevice}, which always
		 * narrows to exactly one device, this can legitimately return several (e.g.
		 * multiple devices sharing a name).
		 *
		 * <p>
		 * Historical note: this originally existed to re-verify results from the old
		 * {@code BleUtils.scan(..., match)} early-stop parameter, which was only an
		 * optimization, not a guaranteed hard filter - on a timeout it silently fell
		 * back to returning every other device it happened to discover along the way,
		 * instead of an empty list. An exact-match selector must never let a command
		 * run against a device other than the one requested (confirmed on real
		 * hardware: an OTA targeting {@code =30:ED:A0:A5:A3:65} was instead sent to an
		 * unrelated device that just happened to answer the scan first), so this
		 * re-filtered and failed closed if the exact match wasn't present.
		 * {@link #scanExact} now enforces that same hard filter natively (via
		 * {@link ScanFilter#withAddress}/{@link ScanFilter#withName}), so this method
		 * is no longer required for correctness against a fresh scan - it remains
		 * useful as a defense-in-depth re-check, or for narrowing an already-fetched
		 * device list without rescanning.
		 *
		 * @param found         the raw result of {@code BleUtils.scan(..., selector)}
		 * @param selector      the exact address or name that was requested
		 * @param scanTimeoutMs the scan timeout used to produce {@code found}, quoted
		 *                      back in any thrown message only
		 * @return only the entries matching {@code selector} exactly (case insensitive)
		 *         - possibly more than one
		 * @throws IOException if none of {@code found} matches {@code selector} exactly
		 */
		public static List<BleDeviceResult> resolveExact(List<BleDeviceResult> found, String selector,
				long scanTimeoutMs) throws IOException {
			String normalized = selector.toUpperCase(Locale.ROOT).trim();
			List<BleDeviceResult> matches = new ArrayList<>();
			for (BleDeviceResult device : found) {
				String address = device.getAddress().toUpperCase(Locale.ROOT).trim();
				String name = device.getName() != null ? device.getName().toUpperCase(Locale.ROOT).trim() : null;
				if (normalized.equals(address) || normalized.equals(name)) {
					matches.add(device);
				}
			}
			if (matches.isEmpty()) {
				throw new IOException("no device with address or name \"" + selector + "\" found advertising service "
						+ cz.bliksoft.hmieink.protocol.Ble.SERVICE_UUID + " within " + scanTimeoutMs + "ms");
			}
			return matches;
		}

		public static BleHmiDevice connect(BleAdapter adapter, String address) throws IOException {
			BleHmiDevice device = new BleHmiDevice(adapter, address);
			device.connect();
			return device;
		}

		public static BleHmiDevice connectAndHandshake(BleAdapter adapter, String address, String adminPin,
				String usagePin) throws IOException {
			BleHmiDevice device = connect(adapter, address);
			handshake(device, adminPin, usagePin);
			return device;
		}
	}
}
