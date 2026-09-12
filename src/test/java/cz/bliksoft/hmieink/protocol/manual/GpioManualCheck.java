package cz.bliksoft.hmieink.protocol.manual;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.GpioConfigureFlags;
import cz.bliksoft.hmieink.protocol.GpioMode;
import cz.bliksoft.hmieink.protocol.GpioPatternFlags;
import cz.bliksoft.hmieink.protocol.HandshakeCapabilities;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.Status;

/**
 * Manual, real-hardware verification of
 * GPIO_CONFIGURE/WRITE/READ/PLAY_PATTERN/EVENT (doc/PROTOCOL.md §15). Assumes
 * an LED (with resistor) on pin 15 (pin -&gt; resistor -&gt; LED -&gt; GND,
 * HIGH=on) and a button on pin 16 (pin -&gt; button -&gt; GND, INPUT_PULLUP,
 * pressed=LOW/idle=HIGH) - the defaults confirmed with the user for this check.
 * Drives the LED directly and via GPIO_PLAY_PATTERN (visual confirmation via
 * {@link cz.bliksoft.hmieink.protocol.manual}-style AskUserQuestion follow-up
 * outside this tool), asserts GPIO_READ_RESPONSE agrees with what was written
 * at every step, exercises the NACK(PIN_UNAVAILABLE)/NACK(BAD_PARAMETERS)
 * validation paths programmatically, then opens a capture window for GPIO_EVENT
 * pushes while the user presses the button. NOT part of the automated
 * {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.GpioManualCheck COM5
 * </pre>
 */
public final class GpioManualCheck {

	private static final int LED_PIN = 15;
	private static final int BUTTON_PIN = 16;
	private static final int UNAVAILABLE_PIN = 99; // not in board::kAvailableGpioPins
	private static final long BUTTON_CAPTURE_WINDOW_MS = 20_000;

	private static int failures = 0;

	private GpioManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: GpioManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		List<int[]> gpioEvents = new CopyOnWriteArrayList<>(); // each entry: {pin, value, timestampMs}
		client.addEventListener(frame -> {
			if (frame.getCommandId() == CommandId.GPIO_EVENT) {
				byte[] p = frame.getPayload();
				int pin = p[0] & 0xFF;
				int value = p[1] & 0xFF;
				long ts = (p[2] & 0xFFL) | ((p[3] & 0xFFL) << 8) | ((p[4] & 0xFFL) << 16) | ((p[5] & 0xFFL) << 24);
				System.out.println("   <- GPIO_EVENT PIN_ID=" + pin + " VALUE=" + value + " TIMESTAMP_MS=" + ts);
				gpioEvents.add(new int[] { pin, value, (int) ts });
			}
		});

		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test)...");
		client.connect();
		try {
			// PIN_TYPE=NONE, PIN_LEN=0 (doc/PROTOCOL.md §5.3) - no pin offered.
			HandshakeCapabilities caps = HandshakeCapabilities
					.parse(client.send(CommandId.HANDSHAKE_REQUEST, new byte[] { 0, 0 }).getPayload());
			byte[] availablePins = caps.getAvailableGpioPins();
			StringBuilder pinsStr = new StringBuilder();
			boolean hasLedPin = false, hasButtonPin = false;
			for (byte b : availablePins) {
				int pin = b & 0xFF;
				pinsStr.append(pin).append(' ');
				hasLedPin |= pin == LED_PIN;
				hasButtonPin |= pin == BUTTON_PIN;
			}
			System.out.println("-> HANDSHAKE_RESPONSE AVAILABLE_GPIO_PINS = [ " + pinsStr + "]");
			check("AVAILABLE_GPIO_PINS includes LED_PIN=" + LED_PIN, hasLedPin);
			check("AVAILABLE_GPIO_PINS includes BUTTON_PIN=" + BUTTON_PIN, hasButtonPin);

			System.out.println("-> GPIO_CONFIGURE PIN_ID=" + LED_PIN + " MODE=OUTPUT");
			send(client, CommandId.GPIO_CONFIGURE, new byte[] { (byte) LED_PIN, (byte) GpioMode.OUTPUT, 0 });

			System.out.println(
					"-> GPIO_CONFIGURE PIN_ID=" + BUTTON_PIN + " MODE=INPUT_PULLUP FLAGS=ENABLE_CHANGE_EVENTS");
			send(client, CommandId.GPIO_CONFIGURE, new byte[] { (byte) BUTTON_PIN, (byte) GpioMode.INPUT_PULLUP,
					(byte) GpioConfigureFlags.ENABLE_CHANGE_EVENTS });

			System.out.println("-> GPIO_WRITE PIN_ID=" + LED_PIN + " VALUE=HIGH - LED should turn ON now");
			send(client, CommandId.GPIO_WRITE, new byte[] { (byte) LED_PIN, 1 });
			assertRead(client, LED_PIN, 1, GpioMode.OUTPUT);
			Thread.sleep(1000);

			System.out.println("-> GPIO_WRITE PIN_ID=" + LED_PIN + " VALUE=LOW - LED should turn OFF now");
			send(client, CommandId.GPIO_WRITE, new byte[] { (byte) LED_PIN, 0 });
			assertRead(client, LED_PIN, 0, GpioMode.OUTPUT);
			Thread.sleep(1000);

			System.out.println("-> GPIO_PLAY_PATTERN PIN_ID=" + LED_PIN
					+ " - 5 blinks, 250ms/step, should end LOW - watch the LED blink");
			int stepMs = 250;
			int stepCount = 10; // 5 full on/off cycles
			byte[] patternPayload = new byte[4 + stepCount * 2];
			patternPayload[0] = (byte) LED_PIN;
			patternPayload[1] = (byte) GpioPatternFlags.INITIAL_LEVEL_HIGH;
			patternPayload[2] = (byte) stepCount;
			patternPayload[3] = 0; // REPEAT_COUNT
			for (int i = 0; i < stepCount; i++) {
				patternPayload[4 + i * 2] = (byte) (stepMs & 0xFF);
				patternPayload[4 + i * 2 + 1] = (byte) ((stepMs >> 8) & 0xFF);
			}
			send(client, CommandId.GPIO_PLAY_PATTERN, patternPayload);
			Thread.sleep((long) stepMs * stepCount + 500); // wait for the pattern (and margin) to finish
			assertRead(client, LED_PIN, 0, GpioMode.OUTPUT);
			System.out.println("   confirmed: pattern finished, pin ended LOW as required by spec");

			System.out.println("-> validation: GPIO_CONFIGURE on an unavailable pin (" + UNAVAILABLE_PIN
					+ ") should NACK(PIN_UNAVAILABLE)");
			expectNack(client, CommandId.GPIO_CONFIGURE,
					new byte[] { (byte) UNAVAILABLE_PIN, (byte) GpioMode.OUTPUT, 0 }, Status.PIN_UNAVAILABLE);

			System.out.println(
					"-> validation: GPIO_CONFIGURE with MODE=PWM_OUTPUT (reserved) should NACK(BAD_PARAMETERS)");
			expectNack(client, CommandId.GPIO_CONFIGURE, new byte[] { (byte) LED_PIN, (byte) GpioMode.PWM_OUTPUT, 0 },
					Status.BAD_PARAMETERS);

			System.out.println(
					"-> validation: GPIO_WRITE on the INPUT-configured button pin should NACK(BAD_PARAMETERS)");
			expectNack(client, CommandId.GPIO_WRITE, new byte[] { (byte) BUTTON_PIN, 1 }, Status.BAD_PARAMETERS);

			int[] buttonRead = readPin(client, BUTTON_PIN);
			System.out.println("-> GPIO_READ_RESPONSE PIN_ID=" + BUTTON_PIN + " VALUE=" + buttonRead[0] + " MODE="
					+ buttonRead[1] + " (idle should read HIGH=1 with INPUT_PULLUP)");

			System.out.println();
			System.out.println("Please press the button on PIN_ID=" + BUTTON_PIN + " a few times over the next "
					+ (BUTTON_CAPTURE_WINDOW_MS / 1000) + " seconds - listening for GPIO_EVENT...");
			Thread.sleep(BUTTON_CAPTURE_WINDOW_MS);

			int[] buttonReadAfter = readPin(client, BUTTON_PIN);
			System.out.println("-> GPIO_READ_RESPONSE PIN_ID=" + BUTTON_PIN + " VALUE=" + buttonReadAfter[0] + " MODE="
					+ buttonReadAfter[1] + " (post-window poll)");

			if (gpioEvents.isEmpty()) {
				System.out
						.println("   NOTE: no GPIO_EVENT frames were captured in the window - either the button wasn't "
								+ "pressed in time, or change-event delivery isn't working. Not counted as a hard "
								+ "failure here (timing-dependent) - confirm with the follow-up question.");
			} else {
				System.out.println("   captured " + gpioEvents.size() + " GPIO_EVENT frame(s) during the window");
				// A real button press should toggle the pin (pressed=LOW, released=HIGH) -
				// consecutive
				// captured values should alternate, not repeat, if debounce is working
				// correctly.
				boolean alternates = true;
				for (int i = 1; i < gpioEvents.size(); i++) {
					if (gpioEvents.get(i)[1] == gpioEvents.get(i - 1)[1]) {
						alternates = false;
						break;
					}
				}
				check("captured GPIO_EVENT values alternate (press/release, no duplicate-fire)", alternates);
			}

			System.out.println();
			if (failures == 0) {
				System.out.println("ALL PROGRAMMATIC CHECKS PASSED (" + gpioEvents.size() + " GPIO_EVENT(s) captured)");
			} else {
				System.out.println("FAILURES: " + failures);
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	private static void assertRead(CommandClient client, int pin, int expectedValue, int expectedMode)
			throws Exception {
		int[] r = readPin(client, pin);
		check("GPIO_READ PIN_ID=" + pin + " VALUE==" + expectedValue + " (was " + r[0] + ")", r[0] == expectedValue);
		check("GPIO_READ PIN_ID=" + pin + " MODE==" + expectedMode + " (was " + r[1] + ")", r[1] == expectedMode);
	}

	private static int[] readPin(CommandClient client, int pin) throws Exception {
		Frame response = client.send(CommandId.GPIO_READ_REQUEST, new byte[] { (byte) pin });
		byte[] p = response.getPayload();
		return new int[] { p[1] & 0xFF, p[2] & 0xFF };
	}

	private static void expectNack(CommandClient client, int commandId, byte[] payload, int expectedStatus)
			throws Exception {
		try {
			client.send(commandId, payload);
			check("expected NACK(0x" + Integer.toHexString(expectedStatus) + ") but got ACK/response", false);
		} catch (CommandNackException e) {
			check("NACK status==0x" + Integer.toHexString(expectedStatus) + " (was 0x"
					+ Integer.toHexString(e.getStatus()) + ")", e.getStatus() == expectedStatus);
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

	private static void check(String description, boolean condition) {
		if (condition) {
			System.out.println("   OK: " + description);
		} else {
			System.out.println("   FAIL: " + description);
			failures++;
		}
	}
}
