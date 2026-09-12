package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;

/**
 * Manual, real-hardware verification of SET_WIFI_CONFIG / WIFI_STATUS_REQUEST /
 * SET_WIFI_ENABLED (doc/PROTOCOL.md §13.2). Deliberately never sends
 * {@code FLAGS.PERSIST} with a test SSID - unlike {@code SET_DEVICE_NAME}, the
 * wire protocol has no "clear back to default" convention for WiFi credentials
 * (SSID_LEN=0 is a validation error, not a clear request), so a persisted test
 * SSID would leave the board unable to rejoin the real network on its own with
 * no way to undo it short of a fresh flash. Instead exercises the safe, real,
 * non-destructive path: toggling SET_WIFI_ENABLED off and back on and
 * confirming the board reassociates with whatever's already configured (falling
 * back to {@code secrets.h} the first time this command family is ever used).
 * NOT part of the automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.WifiConfigManualCheck COM5
 * </pre>
 */
public final class WifiConfigManualCheck {

	private static final long RECONNECT_POLL_INTERVAL_MS = 1000;
	private static final long RECONNECT_TIMEOUT_MS = 20_000;

	private static int failures = 0;

	private WifiConfigManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: WifiConfigManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		SerialFrameTransport transport = new SerialFrameTransport(portDescriptor);
		CommandClient client = new CommandClient(transport);
		System.out.println("Connecting to " + portDescriptor + "...");
		client.connect();
		try {
			WifiStatus baseline = readStatus(client);
			System.out.println("baseline: " + baseline);
			check("baseline ENABLED", baseline.enabled);
			check("baseline CONNECTED (assumes the board already joins a real network at boot)", baseline.connected);

			System.out.println("-> validation: SET_WIFI_CONFIG with SSID_LEN=0 should NACK");
			expectNack(client, buildSetWifiConfigPayload("", "", 0));
			System.out.println("-> validation: SET_WIFI_CONFIG with SSID_LEN=33 (over the 32 max) should NACK");
			expectNack(client, buildSetWifiConfigPayload(repeat('x', 33), "", 0));

			System.out.println("-> SET_WIFI_CONFIG \"TestSsidOnly\" FLAGS=0 (store only, no persist, no connect)");
			client.send(CommandId.SET_WIFI_CONFIG, buildSetWifiConfigPayload("TestSsidOnly", "", 0));
			WifiStatus afterStoreOnly = readStatus(client);
			check("a non-PERSIST, non-CONNECT_NOW SET_WIFI_CONFIG left live state untouched",
					afterStoreOnly.ssid.equals(baseline.ssid) && afterStoreOnly.connected == baseline.connected);

			System.out.println("-> validation: SET_WIFI_ENABLED with a malformed payload should NACK");
			expectNack(client, CommandId.SET_WIFI_ENABLED, new byte[] { 1 });

			System.out.println("-> SET_WIFI_ENABLED(0) FLAGS=0 (session-only)");
			client.send(CommandId.SET_WIFI_ENABLED, new byte[] { 0, 0 });
			WifiStatus disabled = readStatus(client);
			System.out.println("   " + disabled);
			check("ENABLED=0 after SET_WIFI_ENABLED(0)", !disabled.enabled);
			check("CONNECTED=0 after SET_WIFI_ENABLED(0)", !disabled.connected);

			System.out.println("-> SET_WIFI_ENABLED(1) FLAGS=0 (session-only) - polling for reassociation...");
			client.send(CommandId.SET_WIFI_ENABLED, new byte[] { 1, 0 });
			WifiStatus reconnected = pollUntilConnected(client);
			System.out.println("   " + reconnected);
			check("reconnected to the same SSID as baseline", reconnected.ssid.equals(baseline.ssid));

			System.out.println();
			if (failures == 0) {
				System.out.println("ALL CHECKS PASSED");
			} else {
				System.out.println("FAILURES: " + failures);
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	private static WifiStatus pollUntilConnected(CommandClient client) throws Exception {
		long deadline = System.currentTimeMillis() + RECONNECT_TIMEOUT_MS;
		WifiStatus last = readStatus(client);
		while (!last.connected && System.currentTimeMillis() < deadline) {
			Thread.sleep(RECONNECT_POLL_INTERVAL_MS);
			last = readStatus(client);
		}
		check("reconnected within " + (RECONNECT_TIMEOUT_MS / 1000) + "s", last.connected);
		return last;
	}

	private static WifiStatus readStatus(CommandClient client) throws Exception {
		Frame response = client.send(CommandId.WIFI_STATUS_REQUEST, new byte[0]);
		byte[] p = response.getPayload();
		boolean enabled = p[0] != 0;
		boolean connected = p[1] != 0;
		int ssidLen = p[2] & 0xFF;
		String ssid = new String(p, 3, ssidLen, StandardCharsets.UTF_8);
		String ip = (p[3 + ssidLen] & 0xFF) + "." + (p[4 + ssidLen] & 0xFF) + "." + (p[5 + ssidLen] & 0xFF) + "."
				+ (p[6 + ssidLen] & 0xFF);
		return new WifiStatus(enabled, connected, ssid, ip);
	}

	private static byte[] buildSetWifiConfigPayload(String ssid, String password, int flags) {
		byte[] ssidBytes = ssid.getBytes(StandardCharsets.UTF_8);
		byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(1 + ssidBytes.length + 1 + passwordBytes.length + 1);
		payload.put((byte) ssidBytes.length);
		payload.put(ssidBytes);
		payload.put((byte) passwordBytes.length);
		payload.put(passwordBytes);
		payload.put((byte) flags);
		return payload.array();
	}

	private static String repeat(char c, int count) {
		StringBuilder sb = new StringBuilder(count);
		for (int i = 0; i < count; i++) {
			sb.append(c);
		}
		return sb.toString();
	}

	private static void expectNack(CommandClient client, byte[] setWifiConfigPayload) throws Exception {
		expectNack(client, CommandId.SET_WIFI_CONFIG, setWifiConfigPayload);
	}

	private static void expectNack(CommandClient client, int commandId, byte[] payload) throws Exception {
		try {
			client.send(commandId, payload);
			check("expected a NACK but got ACK/response", false);
		} catch (CommandNackException e) {
			check("got NACK(0x" + Integer.toHexString(e.getStatus()) + ")", true);
		}
	}

	private static void check(String description, boolean condition) {
		if (condition) {
			System.out.println("   OK: " + description);
		} else {
			System.out.println("   FAIL: " + description);
			failures++;
		}
	}

	private static final class WifiStatus {
		final boolean enabled;
		final boolean connected;
		final String ssid;
		final String ip;

		WifiStatus(boolean enabled, boolean connected, String ssid, String ip) {
			this.enabled = enabled;
			this.connected = connected;
			this.ssid = ssid;
			this.ip = ip;
		}

		@Override
		public String toString() {
			return "WIFI_STATUS_RESPONSE ENABLED=" + enabled + " CONNECTED=" + connected + " SSID=\"" + ssid + "\" IP="
					+ ip;
		}
	}
}
