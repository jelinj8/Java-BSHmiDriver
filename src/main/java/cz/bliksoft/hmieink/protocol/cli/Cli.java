package cz.bliksoft.hmieink.protocol.cli;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import cz.bliksoft.hmieink.protocol.AuthLevel;
import cz.bliksoft.hmieink.protocol.Ble;
import cz.bliksoft.hmieink.protocol.BleHmiDevice;
import cz.bliksoft.hmieink.protocol.FileHmiDevice;
import cz.bliksoft.hmieink.protocol.HmiDevice;
import cz.bliksoft.hmieink.protocol.SerialHmiDevice;
import cz.bliksoft.hmieink.protocol.TcpHmiDevice;
import cz.bliksoft.hmieink.protocol.script.ScriptRunner;
import cz.bliksoft.javautils.ble.BleAdapter;
import cz.bliksoft.javautils.ble.ScanFilter;
import picocli.CommandLine;

/**
 * Command-line front end for {@link HmiDevice}. Run recipe (mirrors the manual-check tools' own,
 * see {@code CLAUDE.md}) - all four `provided` transport jars are needed since this class
 * references every {@code HmiDevice} subclass:
 *
 * <pre>
 * java -cp target/classes;&lt;jSerialComm jar&gt;;&lt;BSToolbox-BLE jar&gt;;&lt;picocli jar&gt; \
 *     cz.bliksoft.hmieink.protocol.cli.Cli -t serial -a COM5 -c "FAST_CLEAR|WHITE|0"
 * </pre>
 *
 * <p>
 * {@code -t}/{@code -a} are validated via picocli (also giving {@code --help}/{@code --version}).
 * Everything else ({@code -f}/{@code -c}/{@code -p}/{@code -s}) is walked by hand over the raw
 * {@code args[]}, in the exact order they appear on the command line, since picocli's own
 * annotation model collapses repeated *different* options together and loses that interleaving
 * (confirmed against picocli's own docs - see the design plan this class implements). Each line is
 * run through {@link ScriptRunner}, which also recognizes two PC-local pseudo-commands
 * ({@code SLEEP}/{@code WAIT_LOG}, see its own class doc) that control script execution without
 * ever being sent to the device.
 */
public final class Cli {

	private static final long BLE_SCAN_TIMEOUT_MS = 8000;

	@CommandLine.Command(name = "hmi-cli", mixinStandardHelpOptions = true, versionProvider = VersionProvider.class,
			description = "Send commands to a CrowPanel-protocol HMI device, or record them to a local .macro file.")
	static final class Options {
		@CommandLine.Option(names = { "-t", "--transport" }, required = true,
				description = "tcp | serial | ble | file")
		String transport;

		@CommandLine.Option(names = { "-a", "--address" }, required = true,
				description = "host:port (tcp), COM port (serial), device address (ble), or output path (file). "
						+ "For -t ble, -a may also be: 'scan' (list devices and exit), '*' (first device found), "
						+ "'1' (the device if exactly one is found, else error), a name/address substring "
						+ "(exactly one match required), or a comma-separated list of any of those "
						+ "(script run against each, in order)")
		String address;

		@CommandLine.Option(names = { "-k", "--usage-pin" },
				description = "offer this as the usage PIN in the handshake sent right after connecting "
						+ "(doc/PROTOCOL.md §5.3)")
		String usagePin;

		@CommandLine.Option(names = { "-K", "--admin-pin" },
				description = "offer this as the admin PIN in the handshake sent right after connecting "
						+ "(admin implies usage - mutually exclusive with -k)")
		String adminPin;

		@CommandLine.Option(names = { "-f", "--file" },
				description = "read commands from FILE, one per line (also accepts SLEEP|ms and "
						+ "WAIT_LOG|ms[|marker], see ScriptRunner) - repeatable, order-sensitive with -c/-p")
		List<String> files = new ArrayList<>();

		@CommandLine.Option(names = { "-c", "--command" },
				description = "send one inline command, or SLEEP|ms / WAIT_LOG|ms[|marker] (local only, "
						+ "see ScriptRunner) - repeatable, order-sensitive with -f/-p")
		List<String> commands = new ArrayList<>();

		@CommandLine.Option(names = { "-p", "--pipe" },
				description = "also read commands from stdin, one per line - order-sensitive with -f/-c")
		boolean pipe;

		@CommandLine.Option(names = { "-s", "--separator" },
				description = "change the field separator (default |) for everything that follows")
		List<String> separators = new ArrayList<>();
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
		switch (opts.transport.toLowerCase(Locale.ROOT)) {
			case "tcp": {
				String[] hostPort = opts.address.split(":", 2);
				if (hostPort.length != 2) {
					throw new IllegalArgumentException("-a for -t tcp must be host:port, got: " + opts.address);
				}
				try (TcpHmiDevice device = new TcpHmiDevice(hostPort[0], Integer.parseInt(hostPort[1]))) {
					device.connect();
					handshake(device, opts);
					processArgs(device, args);
				}
				return;
			}
			case "serial": {
				try (SerialHmiDevice device = new SerialHmiDevice(opts.address)) {
					device.connect();
					handshake(device, opts);
					processArgs(device, args);
				}
				return;
			}
			case "file": {
				try (FileHmiDevice device = new FileHmiDevice(opts.address)) {
					device.connect();
					handshake(device, opts);
					processArgs(device, args);
				}
				return;
			}
			case "ble": {
				try (BleAdapter adapter = new BleAdapter()) {
					List<BleDevice> found = scanForDevices(adapter);
					if ("scan".equalsIgnoreCase(opts.address)) {
						printScanResults(found);
						return;
					}
					// One scan serves every device in the list; each connection reuses this same
					// `adapter`, which BleFrameTransport requires (it must be the one that scanned).
					for (String selector : opts.address.split(",")) {
						String trimmed = selector.trim();
						if (trimmed.isEmpty()) {
							throw new IllegalArgumentException("empty device selector in -a: '" + opts.address + "'");
						}
						BleDevice target = resolveDevice(found, trimmed);
						System.out.println("Connecting to " + target.address
								+ (target.name != null ? " (" + target.name + ")" : "") + "...");
						try (BleHmiDevice device = new BleHmiDevice(adapter, target.address)) {
							device.connect();
							handshake(device, opts);
							processArgs(device, args);
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
	 * Always handshakes right after connecting - offering the pin from {@code -k}/{@code -K} if
	 * given, else none - since without a real HANDSHAKE_REQUEST the new pin flags would have no
	 * effect at all (doc/PROTOCOL.md §5.3: a connection starts at AuthLevel.NONE until it does).
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

	private static String scanForAddress(BleAdapter adapter, String targetAddress) throws Exception {
		AtomicReference<String> found = new AtomicReference<>();
		adapter.scan(new ScanFilter().withServiceUuid(Ble.SERVICE_UUID), BLE_SCAN_TIMEOUT_MS, (address, name, rssi) -> {
			if (address.equalsIgnoreCase(targetAddress)) {
				found.compareAndSet(null, address);
			}
		});
		String result = found.get();
		if (result == null) {
			throw new IOException("no device with address " + targetAddress + " found advertising service "
					+ Ble.SERVICE_UUID + " within " + BLE_SCAN_TIMEOUT_MS + "ms");
		}
		return result;
	}

	/**
	 * Walks {@code args[]} once, in order, skipping {@code -t}/{@code -a} (and any picocli-handled
	 * help/version flags) and dispatching {@code -f}/{@code -c}/{@code -p}/{@code -s} exactly as
	 * encountered - the ordering guarantee {@link Options}'s own picocli-collected lists can't give.
	 */
	private static void processArgs(HmiDevice device, String[] args) throws IOException {
		ScriptRunner runner = new ScriptRunner(device);
		char separator = '|';
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
	 * A BLE device found during a scan.
	 */
	static final class BleDevice {
		final String address;
		final String name;
		final Integer rssi;

		BleDevice(String address, String name, Integer rssi) {
			this.address = address;
			this.name = name;
			this.rssi = rssi;
		}
	}

	/**
	 * Scan for all BLE devices advertising the CrowPanel service UUID.
	 */
	private static List<BleDevice> scanForDevices(BleAdapter adapter) throws Exception {
		List<BleDevice> found = new ArrayList<>();
		adapter.scan(new ScanFilter().withServiceUuid(Ble.SERVICE_UUID), BLE_SCAN_TIMEOUT_MS, (address, name, rssi) -> {
			found.add(new BleDevice(address, name, rssi));
		});
		return found;
	}

	/**
	 * Print the scan results to stdout in a tabular format.
	 */
	private static void printScanResults(List<BleDevice> found) {
		if (found.isEmpty()) {
			System.out.println("No devices found.");
			return;
		}
		System.out.printf("%-17s %s%n", "Address", "Name");
		System.out.println("------------------- ------------------");
		for (BleDevice device : found) {
			System.out.printf("%-17s %s%n", device.address, device.name != null ? device.name : "(no name)");
		}
	}

	/**
	 * Resolve a device selector to an address.
	 *
	 * @param found the list of devices found during scan
	 * @param selector the selector string from -a (e.g. "*", "1", "device name", "address")
	 * @return the resolved device
	 * @throws IOException if no match or ambiguous match is found
	 */
	public static BleDevice resolveDevice(List<BleDevice> found, String selector) throws IOException {
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
				for (BleDevice d : found) {
					addresses.add(d.address);
				}
				throw new IOException("multiple devices found advertising service " + Ble.SERVICE_UUID
						+ " within " + BLE_SCAN_TIMEOUT_MS + "ms: " + addresses);
			}
			return found.get(0);
		} else {
			List<BleDevice> matches = new ArrayList<>();
			String lowerSelector = selector.toLowerCase(Locale.ROOT);
			for (BleDevice device : found) {
				if (device.address.toLowerCase(Locale.ROOT).contains(lowerSelector)
						|| (device.name != null && device.name.toLowerCase(Locale.ROOT).contains(lowerSelector))) {
					matches.add(device);
				}
			}
			if (matches.isEmpty()) {
				throw new IOException("no device matching \"" + selector + "\" found advertising service "
						+ Ble.SERVICE_UUID + " within " + BLE_SCAN_TIMEOUT_MS + "ms");
			} else if (matches.size() > 1) {
				List<String> addresses = new ArrayList<>();
				for (BleDevice d : matches) {
					addresses.add(d.address);
				}
				throw new IOException("multiple devices matched \"" + selector + "\": " + addresses);
			}
			return matches.get(0);
		}
	}
}
