package cz.bliksoft.hmieink.protocol.manual;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.GpioMode;
import cz.bliksoft.hmieink.protocol.GpioPatternFlags;
import cz.bliksoft.hmieink.protocol.HandshakeCapabilities;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;

/**
 * Manual, real-hardware verification that the onboard status LED (IO41, {@code board::kPinStatusLed})
 * is PC-controllable via §15 GPIO commands, requiring no external LED/wiring - see doc/PROTOCOL.md
 * design note 43 (recorded but not wired to anything) and design note 66's follow-up (now added to
 * {@code board::kAvailableGpioPins[]}). NOT part of the automated {@code mvn test} suite - run it
 * directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.StatusLedManualCheck COM5
 * </pre>
 */
public final class StatusLedManualCheck {

	private static final int STATUS_LED_PIN = 41;

	private StatusLedManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: StatusLedManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test)...");
		client.connect();
		try {
			// PIN_TYPE=NONE, PIN_LEN=0 (doc/PROTOCOL.md §5.3) - no pin offered.
			HandshakeCapabilities caps = HandshakeCapabilities
					.parse(client.send(CommandId.HANDSHAKE_REQUEST, new byte[] { 0, 0 }).getPayload());
			boolean hasStatusLedPin = false;
			for (byte b : caps.getAvailableGpioPins()) {
				if ((b & 0xFF) == STATUS_LED_PIN) {
					hasStatusLedPin = true;
					break;
				}
			}
			if (!hasStatusLedPin) {
				System.err.println("FAILED: AVAILABLE_GPIO_PINS does not include PIN_ID=" + STATUS_LED_PIN);
				System.exit(1);
			}
			System.out.println("OK: AVAILABLE_GPIO_PINS includes PIN_ID=" + STATUS_LED_PIN);

			send(client, CommandId.GPIO_CONFIGURE, new byte[] { (byte) STATUS_LED_PIN, (byte) GpioMode.OUTPUT, 0 });

			System.out.println("-> GPIO_WRITE PIN_ID=" + STATUS_LED_PIN + " VALUE=HIGH - status LED should light");
			send(client, CommandId.GPIO_WRITE, new byte[] { (byte) STATUS_LED_PIN, 1 });
			Thread.sleep(1000);

			System.out.println("-> GPIO_WRITE PIN_ID=" + STATUS_LED_PIN + " VALUE=LOW - status LED should go off");
			send(client, CommandId.GPIO_WRITE, new byte[] { (byte) STATUS_LED_PIN, 0 });
			Thread.sleep(1000);

			System.out.println("-> GPIO_PLAY_PATTERN PIN_ID=" + STATUS_LED_PIN + " - 3 blinks");
			int stepMs = 250;
			int stepCount = 6;
			byte[] patternPayload = new byte[4 + stepCount * 2];
			patternPayload[0] = (byte) STATUS_LED_PIN;
			patternPayload[1] = (byte) GpioPatternFlags.INITIAL_LEVEL_HIGH;
			patternPayload[2] = (byte) stepCount;
			patternPayload[3] = 0;
			for (int i = 0; i < stepCount; i++) {
				patternPayload[4 + i * 2] = (byte) (stepMs & 0xFF);
				patternPayload[4 + i * 2 + 1] = (byte) ((stepMs >> 8) & 0xFF);
			}
			send(client, CommandId.GPIO_PLAY_PATTERN, patternPayload);
			Thread.sleep((long) stepMs * stepCount + 500);

			Frame response = client.send(CommandId.GPIO_READ_REQUEST, new byte[] { (byte) STATUS_LED_PIN });
			byte[] p = response.getPayload();
			int value = p[1] & 0xFF;
			if (value != 0) {
				System.err.println("FAIL: expected PIN_ID=" + STATUS_LED_PIN + " to end LOW after the pattern, was "
						+ value);
				System.exit(1);
			}
			System.out.println("OK: pattern finished, pin ended LOW as required by spec");
			System.out.println();
			System.out.println("ALL PROGRAMMATIC CHECKS PASSED - confirm visually the status LED went on, off, "
					+ "then blinked 3 times.");
		} finally {
			client.close();
		}
	}

	private static void send(CommandClient client, int commandId, byte[] payload) throws Exception {
		try {
			client.send(commandId, payload);
			System.out.println("   ACKed");
		} catch (CommandNackException e) {
			System.err.println("FAILED: commandId=0x" + Integer.toHexString(commandId) + " NACK status=0x"
					+ Integer.toHexString(e.getStatus()));
			System.exit(1);
		}
	}
}
