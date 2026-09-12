package cz.bliksoft.hmieink.protocol.cli;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import cz.bliksoft.hmieink.protocol.AuthLevel;
import cz.bliksoft.hmieink.protocol.Ble;
import cz.bliksoft.hmieink.protocol.BleHmiDevice;
import cz.bliksoft.hmieink.protocol.FileHmiDevice;
import cz.bliksoft.hmieink.protocol.HmiDevice;
import cz.bliksoft.hmieink.protocol.IconSpecCache;
import cz.bliksoft.hmieink.protocol.SerialHmiDevice;
import cz.bliksoft.hmieink.protocol.TcpHmiDevice;
import cz.bliksoft.hmieink.protocol.script.ScriptRunner;
import cz.bliksoft.javautils.ble.BleAdapter;
import cz.bliksoft.javautils.ble.ScanFilter;
import cz.bliksoft.javautils.ble.utils.BleUtils;
import cz.bliksoft.javautils.ble.utils.BleUtils.BleDeviceResult;
import picocli.CommandLine;

/**
 * Command-line front end for {@link HmiDevice}. Run recipe (mirrors the
 * manual-check tools' own, see {@code CLAUDE.md}) - all four `provided`
 * transport jars are needed since this class references every {@code HmiDevice}
 * subclass:
 *
 * <pre>
 * java -cp target/classes;&lt;jSerialComm jar&gt;;&lt;BSToolbox-BLE jar&gt;;&lt;picocli jar&gt; \
 *     cz.bliksoft.hmieink.protocol.cli.Cli -t serial -a COM5 -c "FAST_CLEAR|WHITE|0"
 * </pre>
 *
 * <p>
 * {@code -t}/{@code -a} are validated via picocli (also giving
 * {@code --help}/{@code --version}). Everything else
 * ({@code -f}/{@code -c}/{@code -p}/{@code -s}) is walked by hand over the raw
 * {@code args[]}, in the exact order they appear on the command line, since
 * picocli's own annotation model collapses repeated *different* options
 * together and loses that interleaving (confirmed against picocli's own docs -
 * see the design plan this class implements). Each line is run through
 * {@link ScriptRunner}, which also recognizes PC-local pseudo-commands
 * ({@code SLEEP}/{@code WAIT_LOG}/{@code ICONSPEC}, see its own class doc) that
 * control script execution without ever being sent to the device.
 */
public final class Cli {

	private static final long BLE_SCAN_TIMEOUT_MS = 8000;

	@CommandLine.Command(name = "hmi-cli", mixinStandardHelpOptions = true, versionProvider = VersionProvider.class, description = "Send commands to a CrowPanel-protocol HMI device, or record them to a local .macro file.")
	static final class Options {
		@CommandLine.Option(names = { "-t", "--transport" }, required = true, description = "tcp | serial | ble | file")
		String transport;

		@CommandLine.Option(names = { "-a",
				"--address" }, required = true, description = "host:port (tcp), COM port (serial), or output path (file)\nFor ble:\n"
						+ "\t'scan' (list devices and exit, use 'scan <name/addr>' to filter)\n\t'*' (first device found)\n"
						+ "\t'1' (the device if exactly one is found, else error)\n\t'<name/address substring>' or comma-separated list of those\n\t'=<exact address/name>' for fast connection to known device\n"
						+ "Script will be run against each device found")
		String address;

		@CommandLine.Option(names = { "-k",
				"--usage-pin" }, description = "offer this as the usage PIN in the handshake sent right after connecting "
						+ "(doc/PROTOCOL.md §5.3)")
		String usagePin;

		@CommandLine.Option(names = { "-K",
				"--admin-pin" }, description = "offer this as the admin PIN in the handshake sent right after connecting "
						+ "(admin implies usage - mutually exclusive with -k)")
		String adminPin;

		@CommandLine.Option(names = { "-f",
				"--file" }, description = "read commands from FILE, one per line (also accepts SLEEP|ms, "
						+ "WAIT_LOG|ms[|marker], ICONSPEC|name|spec, and OTA|@firmware.bin, see ScriptRunner) - "
						+ "repeatable, order-sensitive with -c/-p")
		List<String> files = new ArrayList<>();

		@CommandLine.Option(names = { "-c",
				"--command" }, description = "send one inline command, or SLEEP|ms / WAIT_LOG|ms[|marker] / "
						+ "ICONSPEC|name|spec / OTA|@firmware.bin (local only, see ScriptRunner) - repeatable, "
						+ "order-sensitive with -f/-p")
		List<String> commands = new ArrayList<>();

		@CommandLine.Option(names = { "-p",
				"--pipe" }, description = "also read commands from stdin, one per line - order-sensitive with -f/-c")
		boolean pipe;

		@CommandLine.Option(names = { "-s",
				"--separator" }, description = "change the field separator (default |) for subsequent commands - must be exactly one character")
		char separator = '|';

		@CommandLine.Option(names = { "-i",
				"--image-root" }, description = "root directory for resolving relative image paths in ICONSPEC commands")
		String imageRoot;
	}

	static final class VersionProvider implements CommandLine.IVersionProvider {
		@Override
		public String[] getVersion() {
			return new String[] { "bshmiprotocol CLI" };
		}
	}

	private Cli() {
	}

	public static void main(String[] args) {
		CommandLine cl = new CommandLine(new Options());
		CommandLine.ParseResult parseResult;
		try {
			parseResult = cl.parseArgs(args);
		} catch (CommandLine.ParameterException e) {
			System.err.println(e.getMessage());
			e.getCommandLine().usage(System.err);
			System.exit(2);
			return;
		}
		if (CommandLine.printHelpIfRequested(parseResult)) {
			return;
		}
		Options opts = cl.getCommand();
		if (opts.usagePin != null && opts.adminPin != null) {
			System.err.println("error: -k/--usage-pin and -K/--admin-pin are mutually exclusive (admin implies usage)");
			System.exit(2);
			return;
		}
		try {
			runWithDevice(opts, args);
		} catch (Exception e) {
			System.err.println("error: " + e.getMessage());
			System.exit(1);
		}
	}

	private static void runWithDevice(Options opts, String[] args) throws Exception {
		// Set the branding images root for ICONSPEC commands if specified
		if (opts.imageRoot != null) {
			IconSpecCache.setBrandingImagesRoot(opts.imageRoot);
		}
		switch (opts.transport.toLowerCase(Locale.ROOT)) {
		case "tcp": {
			String[] hostPort = opts.address.split(":", 2);
			if (hostPort.length != 2) {
				throw new IllegalArgumentException("-a for -t tcp must be host:port, got: " + opts.address);
			}
			try (TcpHmiDevice device = new TcpHmiDevice(hostPort[0], Integer.parseInt(hostPort[1]))) {
				device.connect();
				handshake(device, opts);
				processArgs(device, opts, args);
			}
			return;
		}
		case "serial": {
			try (SerialHmiDevice device = new SerialHmiDevice(opts.address)) {
				device.connect();
				handshake(device, opts);
				processArgs(device, opts, args);
			}
			return;
		}
		case "file": {
			try (FileHmiDevice device = new FileHmiDevice(opts.address)) {
				device.connect();
				handshake(device, opts);
				processArgs(device, opts, args);
			}
			return;
		}
		case "ble": {
			try (BleAdapter adapter = new BleAdapter()) {
				if (opts.address != null && opts.address.toLowerCase().startsWith("scan")) {

					String addr = opts.address.substring(4);
					boolean search = false;
					if (addr.startsWith(" ")) {
						addr = addr.trim();
						search = true;
					}

					System.out.println("Scanning for BLE devices...");

					List<BleDeviceResult> list = (search
							? BleUtils.find(adapter, new ScanFilter().withServiceUuid(Ble.SERVICE_UUID), addr,
									BLE_SCAN_TIMEOUT_MS)
							: BleUtils.scan(adapter, new ScanFilter().withServiceUuid(Ble.SERVICE_UUID),
									BLE_SCAN_TIMEOUT_MS, null));
					printScanResults(list);
					return;
				}

				List<BleDeviceResult> found = null;
				if (opts.address.startsWith("=")) {
					String exact = opts.address.substring(1);
					found = exactMatch(BleUtils.scan(adapter, new ScanFilter().withServiceUuid(Ble.SERVICE_UUID),
							BLE_SCAN_TIMEOUT_MS, exact), exact);
				} else {
					found = BleUtils.find(adapter, new ScanFilter().withServiceUuid(Ble.SERVICE_UUID), opts.address,
							BLE_SCAN_TIMEOUT_MS);
				}

				for (BleDeviceResult target : found) {
					System.out.println("Connecting to " + target.getAddress()
							+ (target.getName() != null ? " (" + target.getName() + ")" : "") + "...");
					try (BleHmiDevice device = new BleHmiDevice(adapter, target.getAddress())) {
						device.connect();
						handshake(device, opts);
						processArgs(device, opts, args);
					}
				}
			}
			return;
		}
		default:
			throw new IllegalArgumentException(
					"unknown -t transport: " + opts.transport + " (expected tcp|serial|ble|file)");
		}
	}

	/**
	 * Always handshakes right after connecting - offering the pin from
	 * {@code -k}/{@code -K} if given, else none - since without a real
	 * HANDSHAKE_REQUEST the new pin flags would have no effect at all
	 * (doc/PROTOCOL.md §5.3: a connection starts at AuthLevel.NONE until it does).
	 * {@link HmiDevice#handshake(int, String)} itself applies the negotiated
	 * {@code MAX_CHUNK_SIZE} (§5.2) to the transport, so there's nothing
	 * BLE-specific left to do here.
	 */
	private static void handshake(HmiDevice device, Options opts) throws IOException {
		if (opts.adminPin != null) {
			device.handshake(AuthLevel.ADMIN, opts.adminPin);
		} else if (opts.usagePin != null) {
			device.handshake(AuthLevel.USAGE, opts.usagePin);
		} else {
			device.handshake();
		}
	}

	/**
	 * Walks {@code args[]} once, in order, skipping {@code -t}/{@code -a} (and any
	 * picocli-handled help/version flags) and dispatching
	 * {@code -f}/{@code -c}/{@code -p}/{@code -s} exactly as encountered - the
	 * ordering guarantee {@link Options}'s own picocli-collected lists can't give.
	 */
	private static void processArgs(HmiDevice device, Options opts, String[] args) throws IOException {
		ScriptRunner runner = new ScriptRunner(device);
		char separator = opts.separator;
		int i = 0;
		while (i < args.length) {
			String arg = args[i];
			String inlineValue = null;
			String bare = arg;
			int eq = arg.indexOf('=');
			if (arg.startsWith("--") && eq >= 0) {
				bare = arg.substring(0, eq);
				inlineValue = arg.substring(eq + 1);
			}
			switch (bare) {
			case "-t":
			case "--transport":
			case "-a":
			case "--address":
			case "-k":
			case "--usage-pin":
			case "-K":
			case "--admin-pin":
			case "-i":
			case "--image-root":
				i += inlineValue != null ? 1 : 2;
				continue;
			case "-h":
			case "--help":
			case "-V":
			case "--version":
				i += 1;
				continue;
			case "-s":
			case "--separator": {
				String value = inlineValue != null ? inlineValue : args[++i];
				if (value.length() != 1) {
					throw new IllegalArgumentException("-s expects exactly one character, got: " + value);
				}
				separator = value.charAt(0);
				i++;
				continue;
			}
			case "-f":
			case "--file": {
				String path = inlineValue != null ? inlineValue : args[++i];
				runner.runFile(path, separator);
				i++;
				continue;
			}
			case "-c":
			case "--command": {
				String cmd = inlineValue != null ? inlineValue : args[++i];
				runner.runLine(cmd, separator);
				i++;
				continue;
			}
			case "-p":
			case "--pipe":
				runner.runStdin(separator);
				i++;
				continue;
			default:
				throw new IllegalArgumentException("unrecognized argument: " + arg);
			}
		}
	}

	/**
	 * Print the scan results to stdout in a tabular format.
	 *
	 * @param found the list of BLE device results to print
	 */
	static void printScanResults(List<BleDeviceResult> found) {
		if (found.isEmpty()) {
			System.out.println("No devices found.");
			return;
		}
		System.out.printf("%-17s %s%n", "Address", "Name");
		System.out.println("------------------ -------------------");
		for (BleDeviceResult device : found) {
			System.out.printf("%-17s %s%n", device.getAddress(),
					device.getName() != null ? device.getName() : "(no name)");
		}
	}

	/**
	 * Narrows a {@code BleUtils.scan(..., match)} result down to the exact
	 * address/name match it was supposed to guarantee.
	 *
	 * <p>
	 * {@code scan(..., match)} only stops early and returns just the match when
	 * that peripheral is actually seen before the scan timeout elapses; if the
	 * timeout is reached first, it silently falls back to returning every other
	 * device it happened to discover along the way, instead of an empty list. The
	 * {@code "=<address>"} selector must never let a command run against a device
	 * other than the one requested (confirmed on real hardware: an OTA targeting
	 * {@code =30:ED:A0:A5:A3:65} was instead sent to an unrelated device that just
	 * happened to answer the scan first), so re-filter here and fail closed if the
	 * exact match isn't present.
	 *
	 * @param found    the raw result of {@code BleUtils.scan(..., selector)}
	 * @param selector the exact address or name that was requested (without the
	 *                 leading {@code =})
	 * @return only the entries matching {@code selector} exactly (case insensitive)
	 * @throws IOException if none of {@code found} matches {@code selector} exactly
	 */
	private static List<BleDeviceResult> exactMatch(List<BleDeviceResult> found, String selector) throws IOException {
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
					+ Ble.SERVICE_UUID + " within " + BLE_SCAN_TIMEOUT_MS + "ms");
		}
		return matches;
	}

	/**
	 * Resolve a device selector to a device result from a BLE scan.
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
	 * @param found    the list of devices found during scan
	 * @param selector the selector string from -a (e.g. "*", "1", "device name",
	 *                 "address", or comma-separated substrings)
	 * @return the resolved device result
	 * @throws IOException if no match, ambiguous match, or other resolution error
	 */
	public static BleDeviceResult resolveDevice(List<BleDeviceResult> found, String selector) throws IOException {
		if ("*".equals(selector)) {
			if (found.isEmpty()) {
				throw new IOException("no devices found advertising service " + Ble.SERVICE_UUID + " within "
						+ BLE_SCAN_TIMEOUT_MS + "ms");
			}
			return found.get(0);
		} else if ("1".equals(selector)) {
			if (found.size() == 0) {
				throw new IOException("no devices found advertising service " + Ble.SERVICE_UUID + " within "
						+ BLE_SCAN_TIMEOUT_MS + "ms");
			} else if (found.size() > 1) {
				List<String> addresses = new ArrayList<>();
				for (BleDeviceResult d : found) {
					addresses.add(d.getAddress());
				}
				throw new IOException("multiple devices found advertising service " + Ble.SERVICE_UUID + " within "
						+ BLE_SCAN_TIMEOUT_MS + "ms: " + addresses);
			}
			return found.get(0);
		} else {
			List<BleDeviceResult> matches = new ArrayList<>();
			String lowerSelector = selector.toLowerCase(Locale.ROOT);
			for (BleDeviceResult device : found) {
				if (device.getAddress().toLowerCase(Locale.ROOT).contains(lowerSelector) || (device.getName() != null
						&& device.getName().toLowerCase(Locale.ROOT).contains(lowerSelector))) {
					matches.add(device);
				}
			}
			if (matches.isEmpty()) {
				throw new IOException("no device matching \"" + selector + "\" found advertising service "
						+ Ble.SERVICE_UUID + " within " + BLE_SCAN_TIMEOUT_MS + "ms");
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
}
