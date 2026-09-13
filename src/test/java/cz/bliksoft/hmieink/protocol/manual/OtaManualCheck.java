package cz.bliksoft.hmieink.protocol.manual;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.OtaHashAlgo;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.Status;

/**
 * Manual, real-hardware verification of OTA_INSTALL / OTA_APPLY /
 * OTA_STATUS_REQUEST / OTA_CONFIRM / OTA_ROLLBACK (doc/PROTOCOL.md §16).
 * Deliberately OTAs the board with its own *currently running*
 * {@code firmware.bin} - a functional no-op that still exercises the entire
 * real pipeline (transfer, SHA-256 verify, partition write, boot-partition
 * switch, reboot, status reporting, confirm, and rollback) with zero risk of
 * ending up on genuinely different/incompatible firmware. Both OTA partitions
 * end up holding the exact same, already-proven-working image either way.
 * Reboots happen twice (OTA_APPLY, then OTA_ROLLBACK) - the same already-open
 * Serial connection survives both, since only the ESP32 reboots, not the CH340
 * USB-serial adapter (same technique as {@code PowerManagementManualCheck}'s
 * HARD_SLEEP verification). The whole transfer runs at 115200 baud, so a ~1MB
 * image takes roughly 90 seconds - this check is slow by design, not broken.
 * NOT part of the automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.OtaManualCheck COM5 ../firmware/.pio/build/esp32-s3-crowpanel/firmware.bin
 * </pre>
 */
public final class OtaManualCheck {

	private static final long OTA_INSTALL_TIMEOUT_MS = 180_000; // ~1MB @ 115200 baud is slow
	private static final long REBOOT_SETTLE_MS = 13_000; // full boot sequence, same margin as elsewhere

	private static int failures = 0;

	private OtaManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			System.err.println("usage: OtaManualCheck <port, e.g. COM5> <path to firmware.bin>");
			System.exit(2);
		}
		String portDescriptor = args[0];
		byte[] image = Files.readAllBytes(Paths.get(args[1]));
		System.out.println("firmware.bin: " + image.length + " bytes");
		byte[] sha256 = sha256(image);

		SerialFrameTransport transport = new SerialFrameTransport(portDescriptor);
		CommandClient client = new CommandClient(transport);
		System.out.println("Connecting to " + portDescriptor + "...");
		client.connect();
		try {
			int[] baseline = readOtaStatus(client);
			System.out.println("baseline OTA_STATUS_RESPONSE: RUNNING_SLOT=" + baseline[0] + " PENDING_VERIFICATION="
					+ baseline[1]);

			System.out.println("-> validation: OTA_APPLY with nothing staged should NACK(OTA_NOT_STAGED)");
			expectNack(client, CommandId.OTA_APPLY, new byte[0], Status.OTA_NOT_STAGED);

			System.out.println("-> validation: OTA_INSTALL with mismatched HASH_LEN should NACK(BAD_PARAMETERS)");
			expectNack(client, CommandId.OTA_INSTALL,
					buildOtaInstallPayload(new byte[16], OtaHashAlgo.SHA256, 0, new byte[0]), Status.BAD_PARAMETERS);

			System.out.println("-> validation: OTA_INSTALL with a wrong hash should NACK(OTA_HASH_MISMATCH)");
			byte[] wrongHash = sha256.clone();
			wrongHash[0] ^= 0x01;
			expectNack(client, CommandId.OTA_INSTALL, buildOtaInstallPayload(image, OtaHashAlgo.SHA256, 0, wrongHash),
					Status.OTA_HASH_MISMATCH, OTA_INSTALL_TIMEOUT_MS);

			System.out.println("-> OTA_INSTALL (staged, not applied) - transferring " + image.length
					+ " bytes at 115200 baud, this will take a while...");
			long t0 = System.currentTimeMillis();
			client.send(CommandId.OTA_INSTALL, buildOtaInstallPayload(image, OtaHashAlgo.SHA256, 0, sha256),
					OTA_INSTALL_TIMEOUT_MS);
			System.out.println("   staged after " + (System.currentTimeMillis() - t0) + "ms");

			System.out.println("-> validation: a second OTA_INSTALL while one is staged should NACK(BUSY)");
			expectNack(client, CommandId.OTA_INSTALL, buildOtaInstallPayload(image, OtaHashAlgo.NONE, 0, new byte[0]),
					Status.BUSY, OTA_INSTALL_TIMEOUT_MS);

			System.out.println("-> OTA_APPLY - device will reboot into the staged image...");
			client.send(CommandId.OTA_APPLY, new byte[0]);
			System.out.println("   ACKed, waiting " + (REBOOT_SETTLE_MS / 1000) + "s for reboot (same connection, "
					+ "no reconnect needed)...");
			Thread.sleep(REBOOT_SETTLE_MS);

			int[] afterApply = readOtaStatus(client);
			System.out.println("   OTA_STATUS_RESPONSE after apply: RUNNING_SLOT=" + afterApply[0]
					+ " PENDING_VERIFICATION=" + afterApply[1]);
			check("RUNNING_SLOT switched to the other OTA partition", afterApply[0] != baseline[0]);
			// PIN_TYPE=NONE, PIN_LEN=0 (doc/PROTOCOL.md §5.3) - no pin offered.
			check("device is responsive again after the OTA reboot",
					client.send(CommandId.HANDSHAKE_REQUEST, new byte[] { 0, 0 }) != null);

			System.out.println("-> OTA_CONFIRM");
			client.send(CommandId.OTA_CONFIRM, new byte[0]);
			int[] afterConfirm = readOtaStatus(client);
			System.out.println("   OTA_STATUS_RESPONSE after confirm: PENDING_VERIFICATION=" + afterConfirm[1]);
			check("no longer pending verification after OTA_CONFIRM", afterConfirm[1] == 0);

			System.out.println("-> OTA_ROLLBACK - device will reboot back to the previous slot...");
			client.send(CommandId.OTA_ROLLBACK, new byte[0]);
			System.out.println("   ACKed, waiting " + (REBOOT_SETTLE_MS / 1000) + "s for reboot...");
			Thread.sleep(REBOOT_SETTLE_MS);

			int[] afterRollback = readOtaStatus(client);
			System.out.println("   OTA_STATUS_RESPONSE after rollback: RUNNING_SLOT=" + afterRollback[0]
					+ " PENDING_VERIFICATION=" + afterRollback[1]);
			check("RUNNING_SLOT reverted to the original partition", afterRollback[0] == baseline[0]);

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

	private static byte[] sha256(byte[] data) throws NoSuchAlgorithmException {
		return MessageDigest.getInstance("SHA-256").digest(data);
	}

	private static int[] readOtaStatus(CommandClient client) throws Exception {
		Frame response = client.send(CommandId.OTA_STATUS_REQUEST, new byte[0]);
		byte[] p = response.getPayload();
		int runningSlot = p[0] & 0xFF;
		int pendingVerification = p[1] & 0xFF;
		int versionLen = p[2] & 0xFF;
		String version = new String(p, 3, versionLen, StandardCharsets.UTF_8);
		System.out.println("   RUNNING_VERSION=\"" + version + "\"");
		return new int[] { runningSlot, pendingVerification };
	}

	private static byte[] buildOtaInstallPayload(byte[] image, int hashAlgo, int flags, byte[] hash) {
		int hashLen = hash.length;
		byte[] out = new byte[4 + 1 + 1 + hashLen + 1 + image.length];
		int totalLen = image.length;
		out[0] = (byte) (totalLen & 0xFF);
		out[1] = (byte) ((totalLen >> 8) & 0xFF);
		out[2] = (byte) ((totalLen >> 16) & 0xFF);
		out[3] = (byte) ((totalLen >> 24) & 0xFF);
		out[4] = (byte) hashAlgo;
		out[5] = (byte) hashLen;
		System.arraycopy(hash, 0, out, 6, hashLen);
		out[6 + hashLen] = (byte) flags;
		System.arraycopy(image, 0, out, 7 + hashLen, image.length);
		return out;
	}

	private static void expectNack(CommandClient client, int commandId, byte[] payload, int expectedStatus)
			throws Exception {
		expectNack(client, commandId, payload, expectedStatus, 5000);
	}

	private static void expectNack(CommandClient client, int commandId, byte[] payload, int expectedStatus,
			long timeoutMillis) throws Exception {
		try {
			client.send(commandId, payload, timeoutMillis);
			check("expected NACK(0x" + Integer.toHexString(expectedStatus) + ") but got ACK/response", false);
		} catch (CommandNackException e) {
			check("got NACK(0x" + Integer.toHexString(expectedStatus) + ") (was 0x" + Integer.toHexString(e.getStatus())
					+ ")", e.getStatus() == expectedStatus);
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
