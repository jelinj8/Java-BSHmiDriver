package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.HandshakeCapabilities;
import cz.bliksoft.hmieink.protocol.PowerMode;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.Volume;
import cz.bliksoft.hmieink.protocol.WakeReason;

/**
 * Manual, real-hardware verification of the display (GPIO7) and SD (GPIO42)
 * power-rail gating added to SET_POWER_MODE (doc/PROTOCOL.md §17) and
 * StorageManager's SD idle timeout - requested directly: "for full powerdown we
 * can turn them off completely, for light sleep it should be recoverable, in
 * both cases carefully to not breaking filesystem... inactivity powering down
 * the SD would be nice". Confirms a small draw and a full SD
 * upload/list/download/delete round trip both still work correctly: at boot,
 * immediately after a LOW_POWER (light sleep) wake, after the SD
 * idle-power-down timeout fires with no SD activity (the draw check at that
 * point is a plain regression check, not an idle-timeout test of its own - the
 * display's equivalent idle timeout was built, measured against the SSD1683
 * datasheet, and deliberately reverted for ACTIVE mode; see design note 79/80),
 * and after a HARD_SLEEP (deep sleep, full reboot) wake. VOLUME=SD steps are
 * skipped (with a warning, not a failure) if STORAGE_INFO reports no card
 * present. Takes several minutes end to end (the idle-timeout wait alone is the
 * dominant cost) - this is slow by design, not broken. NOT part of the
 * automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.PowerRailManualCheck COM5
 * </pre>
 */
public final class PowerRailManualCheck {

	// Must exceed StorageManager::kIdlePowerDownMs (30000ms) with a comfortable
	// margin.
	private static final long IDLE_WAIT_MS = 35_000;
	private static final long REBOOT_SETTLE_MS = 13_000; // same margin as OtaManualCheck/PowerManagementManualCheck

	private static int failures = 0;
	private static int drawX = 10; // walks rightward each call so successive draws don't overlap

	private PowerRailManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: PowerRailManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		SerialFrameTransport transport = new SerialFrameTransport(portDescriptor);
		CommandClient client = new CommandClient(transport);
		System.out.println("Connecting to " + portDescriptor + "...");
		client.connect();
		try {
			boolean sdPresent = storageInfoPresent(client);
			System.out.println("VOLUME=SD present: " + sdPresent
					+ (sdPresent ? "" : " - SD steps below will be " + "skipped, not failed"));

			System.out.println("-> baseline: draw + SD round trip right after boot");
			checkDraw(client, "baseline draw");
			if (sdPresent) {
				checkSdRoundTrip(client, "baseline SD round trip");
			}

			System.out.println("-> SET_POWER_MODE LOW_POWER, WAKE_AFTER_MS=3000 - display/SD power should be cut "
					+ "then automatically restored on wake");
			client.send(CommandId.SET_POWER_MODE, buildSetPowerModePayload(PowerMode.LOW_POWER, 0, 3000, 0));
			Thread.sleep(4500);
			int wakeReason = readPowerStatusWakeReason(client);
			check("woke via LOW_POWER_TIMER", wakeReason == WakeReason.LOW_POWER_TIMER);

			System.out.println("-> immediately after LOW_POWER wake: draw + SD round trip");
			checkDraw(client, "post-LOW_POWER-wake draw");
			if (sdPresent) {
				checkSdRoundTrip(client, "post-LOW_POWER-wake SD round trip");
			}

			System.out.println("-> waiting " + (IDLE_WAIT_MS / 1000) + "s with no SD/display activity - "
					+ "StorageManager's SD idle timeout should cut power on its own during this wait");
			Thread.sleep(IDLE_WAIT_MS);
			System.out.println("-> draw" + (sdPresent ? " + SD round trip" : "") + " after the idle wait - plain "
					+ "regression check for the draw, lazy-recovery check for SD");
			checkDraw(client, "post-idle-timeout draw");
			if (sdPresent) {
				checkSdRoundTrip(client, "post-idle-timeout SD round trip");
			}

			System.out.println("-> SET_POWER_MODE HARD_SLEEP, WAKE_AFTER_MS=3000 - full reboot, display/SD power "
					+ "cut right before sleeping (no restore needed - boot re-inits both from scratch)");
			client.send(CommandId.SET_POWER_MODE, buildSetPowerModePayload(PowerMode.HARD_SLEEP, 0, 3000, 0));
			System.out.println("   ACKed, waiting " + (3000 + REBOOT_SETTLE_MS) / 1000
					+ "s for timer + reboot (same connection, no reconnect needed)...");
			Thread.sleep(3000 + REBOOT_SETTLE_MS);

			// PIN_TYPE=NONE, PIN_LEN=0 (doc/PROTOCOL.md §5.3) - no pin offered.
			HandshakeCapabilities caps = HandshakeCapabilities
					.parse(client.send(CommandId.HANDSHAKE_REQUEST, new byte[] { 0, 0 }).getPayload());
			check("woke via HARD_SLEEP_TIMER", caps.getLastWakeReason() == WakeReason.HARD_SLEEP_TIMER);

			System.out.println("-> after HARD_SLEEP reboot: draw + SD round trip");
			checkDraw(client, "post-HARD_SLEEP-reboot draw");
			if (sdPresent) {
				checkSdRoundTrip(client, "post-HARD_SLEEP-reboot SD round trip");
			}

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

	private static void checkDraw(CommandClient client, String label) throws Exception {
		int x = drawX;
		drawX += 30;
		try {
			ByteBuffer payload = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
			payload.putShort((short) x);
			payload.putShort((short) 10);
			payload.putShort((short) 20);
			payload.putShort((short) 20);
			payload.put((byte) Color.BLACK);
			payload.put((byte) DrawMode.REPLACE);
			payload.put((byte) 1); // FILLED
			payload.put((byte) 1); // LINE_WIDTH (ignored, filled)
			payload.put((byte) 0x01); // FLAGS: REFRESH_NOW, partial
			client.send(CommandId.DRAW_RECT, payload.array());
			check(label, true);
		} catch (CommandNackException e) {
			System.err.println("   NACK status=0x" + Integer.toHexString(e.getStatus()));
			check(label, false);
		}
	}

	private static void checkSdRoundTrip(CommandClient client, String label) throws Exception {
		String path = "/powerrail_test.txt";
		byte[] content = ("PowerRailManualCheck " + System.currentTimeMillis() + "\n").getBytes(StandardCharsets.UTF_8);
		try {
			upload(client, path, content);
			byte[] downloaded = download(client, path);
			delete(client, path);
			check(label, java.util.Arrays.equals(content, downloaded));
		} catch (CommandNackException e) {
			System.err.println("   NACK status=0x" + Integer.toHexString(e.getStatus()));
			check(label, false);
		}
	}

	private static void upload(CommandClient client, String path, byte[] content) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(1 + 1 + pathBytes.length + 4 + content.length)
				.order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.SD);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		payload.putInt(content.length);
		payload.put(content);
		client.send(CommandId.FILE_UPLOAD, payload.array());
	}

	private static byte[] download(CommandClient client, String path) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.SD);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		Frame response = client.send(CommandId.FILE_DOWNLOAD_REQUEST, payload.array());
		byte[] data = response.getPayload();
		long fileLen = readU32LE(data, 0);
		byte[] out = new byte[(int) fileLen];
		System.arraycopy(data, 4, out, 0, (int) fileLen);
		return out;
	}

	private static void delete(CommandClient client, String path) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.SD);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		client.send(CommandId.FILE_DELETE, payload.array());
	}

	private static boolean storageInfoPresent(CommandClient client) throws Exception {
		Frame response = client.send(CommandId.STORAGE_INFO_REQUEST, new byte[] { (byte) Volume.SD });
		byte[] data = response.getPayload();
		return (data[1] & 0xFF) != 0;
	}

	private static int readPowerStatusWakeReason(CommandClient client) throws Exception {
		Frame response = client.send(CommandId.POWER_STATUS_REQUEST, new byte[0]);
		return response.getPayload()[1] & 0xFF;
	}

	private static byte[] buildSetPowerModePayload(int mode, int flags, long wakeAfterMs, int wakeButton) {
		return new byte[] { (byte) mode, (byte) flags, (byte) (wakeAfterMs & 0xFF), (byte) ((wakeAfterMs >> 8) & 0xFF),
				(byte) ((wakeAfterMs >> 16) & 0xFF), (byte) ((wakeAfterMs >> 24) & 0xFF), (byte) wakeButton };
	}

	private static long readU32LE(byte[] data, int offset) {
		return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8) | ((data[offset + 2] & 0xFFL) << 16)
				| ((data[offset + 3] & 0xFFL) << 24);
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
