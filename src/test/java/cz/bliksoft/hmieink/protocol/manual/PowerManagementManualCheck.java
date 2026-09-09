package cz.bliksoft.hmieink.protocol.manual;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.HandshakeCapabilities;
import cz.bliksoft.hmieink.protocol.PowerMode;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.WakeReason;

/**
 * Manual, real-hardware verification of SET_POWER_MODE / POWER_STATUS_REQUEST (doc/PROTOCOL.md
 * §17). Exercises the two live-testable wake paths - LOW_POWER's timer and Serial-activity wake -
 * over the Serial transport itself, so the same connection can observe the device coming back.
 * HARD_SLEEP (deep sleep) is NOT exercised here: it reboots the MCU, which would drop this test's
 * own Serial connection and needs a fresh {@code connect()} afterward to observe - left as a
 * visual/manual step for the user to confirm separately if wanted, per the note printed at the end.
 * NOT part of the automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.PowerManagementManualCheck COM5
 * </pre>
 */
public final class PowerManagementManualCheck {

	private static int failures = 0;

	private PowerManagementManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: PowerManagementManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		SerialFrameTransport transport = new SerialFrameTransport(portDescriptor);
		CommandClient client = new CommandClient(transport);
		System.out.println("Connecting to " + portDescriptor + "...");
		client.connect();
		try {
			// PIN_TYPE=NONE, PIN_LEN=0 (doc/PROTOCOL.md §5.3) - no pin offered.
			HandshakeCapabilities baselineCaps = HandshakeCapabilities
					.parse(client.send(CommandId.HANDSHAKE_REQUEST, new byte[] { 0, 0 }).getPayload());
			int baselineWakeReason = baselineCaps.getLastWakeReason();
			System.out.println("baseline HANDSHAKE_RESPONSE.LAST_WAKE_REASON=" + describeWakeReason(baselineWakeReason));

			int[] status = readPowerStatus(client);
			System.out.println("baseline POWER_STATUS_RESPONSE: CURRENT_MODE=" + status[0] + " LAST_WAKE_REASON="
					+ describeWakeReason(status[1]));
			check("CURRENT_MODE is ACTIVE", status[0] == PowerMode.ACTIVE);
			check("handshake and POWER_STATUS_REQUEST agree on LAST_WAKE_REASON", status[1] == baselineWakeReason);

			System.out.println("-> validation: SET_POWER_MODE with an invalid MODE should NACK");
			expectNack(client, buildSetPowerModePayload(0x7F, 0, 0, 0));
			System.out.println("-> validation: SET_POWER_MODE HARD_SLEEP with an unresolvable WAKE_BUTTON should NACK");
			expectNack(client, buildSetPowerModePayload(PowerMode.HARD_SLEEP, 0, 0, 0x20));

			System.out.println("-> SET_POWER_MODE ACTIVE (no-op) should ACK");
			client.send(CommandId.SET_POWER_MODE, buildSetPowerModePayload(PowerMode.ACTIVE, 0, 0, 0));

			System.out.println("-> SET_POWER_MODE LOW_POWER, WAKE_AFTER_MS=3000 - device should light-sleep ~3s");
			long t0 = System.currentTimeMillis();
			client.send(CommandId.SET_POWER_MODE, buildSetPowerModePayload(PowerMode.LOW_POWER, 0, 3000, 0));
			System.out.println("   ACKed after " + (System.currentTimeMillis() - t0) + "ms (should be fast - ACK "
					+ "happens before sleeping, per §17.1)");

			Thread.sleep(4500); // WAKE_AFTER_MS + margin - no wake preamble needed, it already woke
								 // itself via the timer, not UART activity
			int[] afterTimer = readPowerStatus(client);
			System.out.println("   POWER_STATUS_RESPONSE after timer wake: LAST_WAKE_REASON="
					+ describeWakeReason(afterTimer[1]));
			check("woke via LOW_POWER_TIMER", afterTimer[1] == WakeReason.LOW_POWER_TIMER);

			System.out.println(
					"-> SET_POWER_MODE LOW_POWER, WAKE_AFTER_MS=0 (wait indefinitely) - waking via Serial activity");
			client.send(CommandId.SET_POWER_MODE, buildSetPowerModePayload(PowerMode.LOW_POWER, 0, 0, 0));
			Thread.sleep(500); // let it actually enter sleep before we try to wake it
			transport.sendWakePreamble();
			Thread.sleep(300); // give the UART wake hardware real wall-clock time to stabilize
			int[] afterSerial = readPowerStatus(client);
			System.out.println("   POWER_STATUS_RESPONSE after Serial-activity wake: LAST_WAKE_REASON="
					+ describeWakeReason(afterSerial[1]));
			check("woke via LOW_POWER_SERIAL_ACTIVITY", afterSerial[1] == WakeReason.LOW_POWER_SERIAL_ACTIVITY);

			System.out.println();
			System.out.println("NOTE: HARD_SLEEP (deep sleep) was not exercised - it reboots the MCU, dropping "
					+ "this test's own connection. If you want to confirm it separately: send SET_POWER_MODE "
					+ "MODE=HARD_SLEEP with a WAKE_AFTER_MS, wait it out, then reconnect and check "
					+ "HANDSHAKE_RESPONSE.LAST_WAKE_REASON == HARD_SLEEP_TIMER (0x01).");

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

	private static int[] readPowerStatus(CommandClient client) throws Exception {
		Frame response = client.send(CommandId.POWER_STATUS_REQUEST, new byte[0]);
		byte[] p = response.getPayload();
		return new int[] { p[0] & 0xFF, p[1] & 0xFF };
	}

	private static byte[] buildSetPowerModePayload(int mode, int flags, long wakeAfterMs, int wakeButton) {
		return new byte[] { (byte) mode, (byte) flags, (byte) (wakeAfterMs & 0xFF),
				(byte) ((wakeAfterMs >> 8) & 0xFF), (byte) ((wakeAfterMs >> 16) & 0xFF),
				(byte) ((wakeAfterMs >> 24) & 0xFF), (byte) wakeButton };
	}

	private static String describeWakeReason(int reason) {
		switch (reason) {
			case WakeReason.POWER_ON:
				return "POWER_ON (0x" + Integer.toHexString(reason) + ")";
			case WakeReason.HARD_SLEEP_TIMER:
				return "HARD_SLEEP_TIMER (0x" + Integer.toHexString(reason) + ")";
			case WakeReason.HARD_SLEEP_BUTTON:
				return "HARD_SLEEP_BUTTON (0x" + Integer.toHexString(reason) + ")";
			case WakeReason.HARD_SLEEP_EXTERNAL_RESET:
				return "HARD_SLEEP_EXTERNAL_RESET (0x" + Integer.toHexString(reason) + ")";
			case WakeReason.LOW_POWER_TIMER:
				return "LOW_POWER_TIMER (0x" + Integer.toHexString(reason) + ")";
			case WakeReason.LOW_POWER_SERIAL_ACTIVITY:
				return "LOW_POWER_SERIAL_ACTIVITY (0x" + Integer.toHexString(reason) + ")";
			case WakeReason.LOW_POWER_BLE_ACTIVITY:
				return "LOW_POWER_BLE_ACTIVITY (0x" + Integer.toHexString(reason) + ")";
			default:
				return "0x" + Integer.toHexString(reason);
		}
	}

	private static void expectNack(CommandClient client, byte[] setPowerModePayload) throws Exception {
		try {
			client.send(CommandId.SET_POWER_MODE, setPowerModePayload);
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
}
