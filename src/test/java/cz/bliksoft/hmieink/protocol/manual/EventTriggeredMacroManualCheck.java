package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.MacroCodec;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.Volume;

/**
 * Manual, real-hardware verification of event-triggered macro auto-play (doc/PROTOCOL.md §18.5):
 * on any {@code BUTTON_EVENT}/{@code GPIO_EVENT}, firmware auto-plays a same-named macro from
 * {@code VOLUME=PSRAM} if one exists. Uses {@link SerialFrameTransport#pressBoot()}/
 * {@code releaseBoot()} (already proven in {@code BoardControlManualCheck}) to simulate real
 * {@code BUTTON_EVENT}s for {@code BUTTON_ID.BOOT} (id {@code 0x04}) via the CH340's DTR line, with
 * no physical touch needed - GPIO-triggered playback needs a real level change on a wired input pin
 * and is out of scope here (physical/manual verification only).
 *
 * <p>
 * Asserts programmatically throughout (via {@code LOG_MESSAGE} echoes and downloading
 * {@code /trigger_id.txt}), like {@code StorageManualCheck}, rather than relying on a human looking
 * at the panel. Covers: {@code SHORT_PRESS} firing a dedicated macro after a quick tap; a trigger
 * arriving while a macro is already playing being queued (appended to the tail of the current
 * playback) rather than dropped; and the {@code /on_any_event.macro} fallback + {@code
 * /trigger_id.txt} "last trigger" variable. NOT part of the automated {@code mvn test} suite - run
 * it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.EventTriggeredMacroManualCheck COM5
 * </pre>
 */
public final class EventTriggeredMacroManualCheck {

	private static final int BOOT_BUTTON_ID = 0x04;
	private static final String SHORT_PRESS_PATH = "/on_button_4_shortpress.macro";
	private static final String PRESS_PATH = "/on_button_4_press.macro";
	private static final String RELEASE_PATH = "/on_button_4_release.macro";
	private static final String LONGPRESS_PATH = "/on_button_4_longpress.macro";
	private static final String ANY_EVENT_PATH = "/on_any_event.macro";
	private static final String TRIGGER_ID_PATH = "/trigger_id.txt";

	private static int failures = 0;

	private EventTriggeredMacroManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: EventTriggeredMacroManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		SerialFrameTransport transport = new SerialFrameTransport(portDescriptor);
		CommandClient client = new CommandClient(transport, 3000);
		System.out.println("Connecting to " + portDescriptor + "...");
		client.connect();
		try {
			System.out.println("-> cleanup: deleting any pre-existing test files on VOLUME=PSRAM");
			for (String path : Arrays.asList(SHORT_PRESS_PATH, PRESS_PATH, RELEASE_PATH, LONGPRESS_PATH,
					ANY_EVENT_PATH, TRIGGER_ID_PATH)) {
				deleteIfExists(client, path);
			}

			testShortPress(client, transport);
			testQueueingWhileBusy(client, transport);
			testFallbackAndTriggerId(client, transport);

			System.out.println();
			if (failures == 0) {
				System.out.println("ALL CHECKS PASSED");
			} else {
				System.out.println(failures + " CHECK(S) FAILED - see above");
				System.exit(1);
			}
		} finally {
			for (String path : Arrays.asList(SHORT_PRESS_PATH, PRESS_PATH, RELEASE_PATH, LONGPRESS_PATH,
					ANY_EVENT_PATH, TRIGGER_ID_PATH)) {
				deleteIfExists(client, path);
			}
			client.close();
		}
	}

	// SHORT_PRESS (doc/PROTOCOL.md §11) fires right after RELEASE whenever a press-release cycle
	// never crosses the LONG_PRESS threshold - a quick tap should trigger its own dedicated macro.
	private static void testShortPress(CommandClient client, SerialFrameTransport transport) throws Exception {
		System.out.println("== SHORT_PRESS triggers /on_button_4_shortpress.macro ==");
		upload(client, SHORT_PRESS_PATH, macroOf(logMessageEntry("evt_shortpress")));

		System.out.println("-> quick BOOT tap (press, 100ms, release)");
		transport.pressBoot();
		Thread.sleep(100);
		transport.releaseBoot();

		waitAndCheck(client, "evt_shortpress", "SHORT_PRESS auto-played its dedicated macro");
		deleteIfExists(client, SHORT_PRESS_PATH);
		Thread.sleep(200);
	}

	// A second trigger arriving while gMacroPlayer is already playing must queue (append to the
	// tail of the current playback) rather than being dropped - the design decision this whole
	// feature's busy behavior was revised to, mid-design, per the user's own proposal.
	private static void testQueueingWhileBusy(CommandClient client, SerialFrameTransport transport) throws Exception {
		System.out.println("== a second trigger while busy queues instead of dropping ==");
		upload(client, PRESS_PATH, macroOf(pauseEntry(1500), logMessageEntry("evt1")));

		System.out.println("-> BOOT press (starts a ~1.5s-paused playback)");
		transport.pressBoot();
		Thread.sleep(300);

		System.out.println("-> overwriting " + PRESS_PATH + " with a second macro while the first is still paused");
		upload(client, PRESS_PATH, macroOf(logMessageEntry("evt2")));

		System.out.println("-> a second BOOT press while the first playback is still mid-PAUSE");
		transport.releaseBoot();
		Thread.sleep(50);
		transport.pressBoot();

		waitAndCheck(client, "evt1", "first (already-playing) macro's LOG_MESSAGE arrived");
		waitAndCheck(client, "evt2", "second (queued) macro's LOG_MESSAGE also arrived - not dropped");

		transport.releaseBoot();
		deleteIfExists(client, PRESS_PATH);
		Thread.sleep(200);
	}

	// /on_any_event.macro plays as a fallback whenever the specific per-event macro is absent, and
	// /trigger_id.txt is overwritten with the specific path that would have applied either way -
	// checked here by downloading it directly (programmatic, not a LOG_MESSAGE echo).
	private static void testFallbackAndTriggerId(CommandClient client, SerialFrameTransport transport) throws Exception {
		System.out.println("== on_any_event.macro fallback + trigger_id.txt ==");
		upload(client, ANY_EVENT_PATH, macroOf(logMessageEntry("evt_fallback")));

		System.out.println("-> BOOT press (no /on_button_4_press.macro staged - expect fallback)");
		transport.pressBoot();
		waitAndCheck(client, "evt_fallback", "PRESS fell back to on_any_event.macro");
		checkTriggerId(client, PRESS_PATH, "PRESS");

		System.out.println("-> holding past the LONG_PRESS threshold, then releasing (isolates RELEASE - no "
				+ "SHORT_PRESS follows a LONG_PRESS cycle)");
		Thread.sleep(1000);
		transport.releaseBoot();
		Thread.sleep(300);
		checkTriggerId(client, RELEASE_PATH, "RELEASE (the last event in this press-hold-release cycle)");
	}

	private static void checkTriggerId(CommandClient client, String expectedPath, String label) throws Exception {
		byte[] expected = expectedPath.getBytes(StandardCharsets.UTF_8);
		byte[] actual = download(client, TRIGGER_ID_PATH);
		check(label + ": trigger_id.txt reads \"" + expectedPath + "\"", Arrays.equals(expected, actual));
	}

	private static void waitAndCheck(CommandClient client, String marker, String label) {
		try {
			client.waitForLogMessage(marker.getBytes(StandardCharsets.UTF_8), 3000);
			check(label, true);
		} catch (Exception e) {
			check(label + " (timed out: " + e.getMessage() + ")", false);
		}
	}

	private static void check(String label, boolean ok) {
		System.out.println("   [" + (ok ? "PASS" : "FAIL") + "] " + label);
		if (!ok) {
			failures++;
		}
	}

	private static MacroCodec.Entry logMessageEntry(String marker) {
		return new MacroCodec.Entry(CommandId.LOG_MESSAGE, marker.getBytes(StandardCharsets.UTF_8));
	}

	private static MacroCodec.Entry pauseEntry(int durationMs) {
		ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(durationMs);
		return new MacroCodec.Entry(CommandId.PAUSE, buf.array());
	}

	private static byte[] macroOf(MacroCodec.Entry... entries) {
		List<MacroCodec.Entry> list = Arrays.asList(entries);
		return MacroCodec.encode(Collections.unmodifiableList(list));
	}

	private static void upload(CommandClient client, String path, byte[] content) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length + 4 + content.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.PSRAM);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		payload.putInt(content.length);
		payload.put(content);
		client.send(CommandId.FILE_UPLOAD, payload.array());
	}

	private static byte[] download(CommandClient client, String path) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.PSRAM);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		Frame response = client.send(CommandId.FILE_DOWNLOAD_REQUEST, payload.array());
		byte[] data = response.getPayload();
		long fileLen = readU32LE(data, 0);
		byte[] out = new byte[(int) fileLen];
		System.arraycopy(data, 4, out, 0, (int) fileLen);
		return out;
	}

	private static void deleteIfExists(CommandClient client, String path) {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.PSRAM);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		try {
			client.send(CommandId.FILE_DELETE, payload.array());
		} catch (CommandNackException e) {
			// not present - fine, this is best-effort cleanup
		} catch (Exception e) {
			System.err.println("   (cleanup delete of " + path + " failed: " + e.getMessage() + ")");
		}
	}

	private static long readU32LE(byte[] data, int offset) {
		return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8) | ((data[offset + 2] & 0xFFL) << 16)
				| ((data[offset + 3] & 0xFFL) << 24);
	}
}
