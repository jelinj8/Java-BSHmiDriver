package cz.bliksoft.hmieink.protocol.manual;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import cz.bliksoft.hmieink.protocol.ButtonEventType;
import cz.bliksoft.hmieink.protocol.ButtonId;
import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;

/**
 * Manual, real-hardware verification of BUTTON_EVENT (doc/PROTOCOL.md §11) - the board's six
 * observable physical buttons (Menu, Back, the dial switch's Up/Down/Confirm, and BOOT - safe to
 * read as an ordinary button once running, since it's only sampled as a strap pin at reset; RESET
 * itself is excluded since pressing it resets the whole MCU, so firmware can never observe it).
 * Unlike every other manual
 * check, there's nothing to send: this just opens a capture window, asks the user to press each
 * button (plus one long-press), and reports exactly what BUTTON_ID/EVENT_TYPE/TIMESTAMP_MS
 * sequence arrived via {@code CommandEventListener} - the same unsolicited-push path
 * {@code GpioManualCheck} exercised for GPIO_EVENT. NOT part of the automated {@code mvn test}
 * suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.ButtonManualCheck COM5
 * </pre>
 */
public final class ButtonManualCheck {

	private static final long CAPTURE_WINDOW_MS = 40_000;
	private static final Map<Integer, String> BUTTON_NAMES = buildButtonNames();
	private static final Map<Integer, String> EVENT_NAMES = buildEventNames();

	private ButtonManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: ButtonManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		List<int[]> events = new CopyOnWriteArrayList<>(); // each entry: {buttonId, eventType, timestampMs}
		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		client.addEventListener(frame -> {
			if (frame.getCommandId() == CommandId.BUTTON_EVENT) {
				byte[] p = frame.getPayload();
				int buttonId = p[0] & 0xFF;
				int eventType = p[1] & 0xFF;
				long ts = (p[2] & 0xFFL) | ((p[3] & 0xFFL) << 8) | ((p[4] & 0xFFL) << 16) | ((p[5] & 0xFFL) << 24);
				System.out.println("   <- BUTTON_EVENT BUTTON_ID=" + describeButton(buttonId) + " EVENT_TYPE="
						+ describeEvent(eventType) + " TIMESTAMP_MS=" + ts);
				events.add(new int[] { buttonId, eventType, (int) ts });
			}
		});

		System.out.println("Connecting to " + portDescriptor + " at " + SerialFrameTransport.DEFAULT_BAUD_RATE
				+ " baud (this resets the board and re-runs its boot self-test)...");
		client.connect();
		try {
			System.out.println();
			System.out.println("Please, over the next " + (CAPTURE_WINDOW_MS / 1000) + " seconds:");
			System.out.println("  - press MENU once");
			System.out.println("  - press BACK once");
			System.out.println("  - press the dial UP once");
			System.out.println("  - press the dial DOWN once");
			System.out.println("  - press the dial CONFIRM once briefly, then once more and HOLD it for "
					+ "over a second (for a LONG_PRESS)");
			System.out.println("  - press BOOT once (a quick, deliberate tap - try not to hold it)");
			System.out.println("Listening...");
			Thread.sleep(CAPTURE_WINDOW_MS);

			System.out.println();
			if (events.isEmpty()) {
				System.out.println("FAIL: no BUTTON_EVENT frames captured at all in the window.");
				System.exit(1);
			}

			Map<Integer, Integer> pressCounts = new LinkedHashMap<>();
			Map<Integer, Integer> longPressCounts = new LinkedHashMap<>();
			for (int[] e : events) {
				if (e[1] == ButtonEventType.PRESS) {
					pressCounts.merge(e[0], 1, Integer::sum);
				} else if (e[1] == ButtonEventType.LONG_PRESS) {
					longPressCounts.merge(e[0], 1, Integer::sum);
				}
			}
			System.out.println("Captured " + events.size() + " BUTTON_EVENT frame(s) total.");
			System.out.println("PRESS counts by button:");
			for (Map.Entry<Integer, String> b : BUTTON_NAMES.entrySet()) {
				int presses = pressCounts.getOrDefault(b.getKey(), 0);
				int longPresses = longPressCounts.getOrDefault(b.getKey(), 0);
				System.out.println("   " + b.getValue() + ": " + presses + " press(es), " + longPresses
						+ " long-press(es)");
			}

			int distinctButtonsPressed = pressCounts.size();
			int totalLongPresses = longPressCounts.values().stream().mapToInt(Integer::intValue).sum();
			System.out.println();
			System.out.println(distinctButtonsPressed + " distinct button(s) reported at least one PRESS; "
					+ totalLongPresses + " LONG_PRESS event(s) total. Compare against what you actually pressed.");
		} finally {
			client.close();
		}
	}

	private static String describeButton(int buttonId) {
		String name = BUTTON_NAMES.get(buttonId);
		return (name != null ? name : "0x" + Integer.toHexString(buttonId)) + " (0x" + Integer.toHexString(buttonId)
				+ ")";
	}

	private static String describeEvent(int eventType) {
		String name = EVENT_NAMES.get(eventType);
		return name != null ? name : "0x" + Integer.toHexString(eventType);
	}

	private static Map<Integer, String> buildButtonNames() {
		Map<Integer, String> m = new LinkedHashMap<>();
		m.put(ButtonId.MENU, "MENU");
		m.put(ButtonId.BACK, "BACK");
		m.put(ButtonId.DIAL_UP, "DIAL_UP");
		m.put(ButtonId.DIAL_DOWN, "DIAL_DOWN");
		m.put(ButtonId.DIAL_SWITCH, "DIAL_SWITCH");
		m.put(ButtonId.BOOT, "BOOT");
		return m;
	}

	private static Map<Integer, String> buildEventNames() {
		Map<Integer, String> m = new LinkedHashMap<>();
		m.put(ButtonEventType.PRESS, "PRESS");
		m.put(ButtonEventType.RELEASE, "RELEASE");
		m.put(ButtonEventType.LONG_PRESS, "LONG_PRESS");
		m.put(ButtonEventType.SHORT_PRESS, "SHORT_PRESS");
		return m;
	}
}
