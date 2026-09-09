package cz.bliksoft.hmieink.protocol.manual;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;

/**
 * Manual, real-hardware verification of SET_BLE_ENABLED / BLE_STATUS_REQUEST / SET_BLE_PIN
 * (doc/PROTOCOL.md §13.2), run over Serial specifically so disabling BLE (which drops any BLE
 * central) never risks disrupting the connection this test itself uses. Only exercises the
 * wire-level surface of SET_BLE_PIN (accept, persist, report via HAS_PIN, validate) - actual
 * pairing enforcement only applies at the *next* boot (see setupBle()'s own comment on why it's
 * not live) and needs a real BLE central attempting to connect post-reboot to observe, which this
 * automated tool deliberately does not attempt (see design note 75). Always clears the PIN back
 * off at the end so a real BLE central (including BleHandshakeManualCheck) isn't left needing to
 * satisfy pairing after this test runs. NOT part of the automated {@code mvn test} suite - run it
 * directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.BleConfigManualCheck COM5
 * </pre>
 */
public final class BleConfigManualCheck {

	private static int failures = 0;

	private BleConfigManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: BleConfigManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		SerialFrameTransport transport = new SerialFrameTransport(portDescriptor);
		CommandClient client = new CommandClient(transport);
		System.out.println("Connecting to " + portDescriptor + "...");
		client.connect();
		try {
			BleStatus baseline = readStatus(client);
			System.out.println("baseline: " + baseline);
			check("baseline ENABLED", baseline.enabled);
			check("baseline HAS_PIN=false", !baseline.hasPin);
			check("BLE_ADDRESS is non-zero", !baseline.address.equals("00:00:00:00:00:00"));

			System.out.println("-> validation: SET_BLE_PIN with PIN=1000000 (over the 999999 max) should NACK");
			expectNack(client, CommandId.SET_BLE_PIN, buildSetBlePinPayload(true, 1_000_000));
			System.out.println("-> validation: SET_BLE_ENABLED with a malformed payload should NACK");
			expectNack(client, CommandId.SET_BLE_ENABLED, new byte[] { 1 });

			System.out.println("-> SET_BLE_ENABLED(0) FLAGS=0 (session-only)");
			client.send(CommandId.SET_BLE_ENABLED, new byte[] { 0, 0 });
			BleStatus disabled = readStatus(client);
			System.out.println("   " + disabled);
			check("ENABLED=0 after SET_BLE_ENABLED(0)", !disabled.enabled);

			System.out.println("-> SET_BLE_ENABLED(1) FLAGS=0 (session-only)");
			client.send(CommandId.SET_BLE_ENABLED, new byte[] { 1, 0 });
			BleStatus reenabled = readStatus(client);
			System.out.println("   " + reenabled);
			check("ENABLED=1 after SET_BLE_ENABLED(1)", reenabled.enabled);

			System.out.println("-> SET_BLE_PIN HAS_PIN=1 PIN=123456");
			client.send(CommandId.SET_BLE_PIN, buildSetBlePinPayload(true, 123456));
			BleStatus withPin = readStatus(client);
			System.out.println("   " + withPin);
			check("HAS_PIN=1 after SET_BLE_PIN(HAS_PIN=1, ...)", withPin.hasPin);

			System.out.println("-> SET_BLE_PIN HAS_PIN=0 (cleanup - clear the PIN back off)");
			client.send(CommandId.SET_BLE_PIN, buildSetBlePinPayload(false, 0));
			BleStatus cleared = readStatus(client);
			System.out.println("   " + cleared);
			check("HAS_PIN=0 after cleanup", !cleared.hasPin);

			System.out.println();
			System.out.println("NOTE: SET_BLE_PIN's actual pairing enforcement only applies at the next boot and was "
					+ "NOT exercised here (would need a real BLE central attempting to connect post-reboot) - "
					+ "the PIN was cleared again immediately above regardless.");

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

	private static BleStatus readStatus(CommandClient client) throws Exception {
		Frame response = client.send(CommandId.BLE_STATUS_REQUEST, new byte[0]);
		byte[] p = response.getPayload();
		boolean enabled = p[0] != 0;
		boolean connected = p[1] != 0;
		boolean hasPin = p[2] != 0;
		StringBuilder addr = new StringBuilder();
		for (int i = 0; i < 6; i++) {
			if (i > 0) {
				addr.append(':');
			}
			addr.append(String.format("%02X", p[3 + i] & 0xFF));
		}
		return new BleStatus(enabled, connected, hasPin, addr.toString());
	}

	private static byte[] buildSetBlePinPayload(boolean hasPin, long pin) {
		byte[] payload = new byte[6];
		payload[0] = (byte) (hasPin ? 1 : 0);
		payload[1] = (byte) (pin & 0xFF);
		payload[2] = (byte) ((pin >> 8) & 0xFF);
		payload[3] = (byte) ((pin >> 16) & 0xFF);
		payload[4] = (byte) ((pin >> 24) & 0xFF);
		payload[5] = 0; // FLAGS - unused by SET_BLE_PIN (always persists), see design note 75
		return payload;
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

	private static final class BleStatus {
		final boolean enabled;
		final boolean connected;
		final boolean hasPin;
		final String address;

		BleStatus(boolean enabled, boolean connected, boolean hasPin, String address) {
			this.enabled = enabled;
			this.connected = connected;
			this.hasPin = hasPin;
			this.address = address;
		}

		@Override
		public String toString() {
			return "BLE_STATUS_RESPONSE ENABLED=" + enabled + " CONNECTED=" + connected + " HAS_PIN=" + hasPin
					+ " BLE_ADDRESS=" + address;
		}
	}
}
