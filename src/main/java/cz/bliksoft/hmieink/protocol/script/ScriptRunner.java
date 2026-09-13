package cz.bliksoft.hmieink.protocol.script;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.FontId;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.HandshakeCapabilities;
import cz.bliksoft.hmieink.protocol.HmiDevice;
import cz.bliksoft.hmieink.protocol.IconSpecCache;
import cz.bliksoft.hmieink.protocol.OtaHashAlgo;
import cz.bliksoft.hmieink.protocol.TextAlign;
import cz.bliksoft.hmieink.protocol.Volume;
import cz.bliksoft.hmieink.protocol.WriteFlags;
import cz.bliksoft.hmieink.protocol.sync.FolderSync;
import cz.bliksoft.hmieink.protocol.sync.SyncMode;
import cz.bliksoft.hmieink.protocol.sync.SyncResult;
import cz.bliksoft.hmieink.protocol.text.TextCommandFormat;

/**
 * Runs a sequence of text command lines (doc-external plaintext notation, see
 * {@link TextCommandFormat}) against an {@link HmiDevice} - the shared engine
 * behind the CLI's {@code -f}/{@code -c}/{@code -p}, usable directly from Java
 * too. Blank lines and lines starting with {@code #} are skipped.
 *
 * <p>
 * PC-local pseudo-commands control execution but are never sent to the device.
 * They are recognized by name before a line ever reaches
 * {@link HmiDevice#sendText} - none of these names exist as real
 * {@code CommandId}s, so there's no collision risk:
 *
 * <ul>
 * <li>{@code SLEEP|<durationMs>} - blocks the script (not the device) for that
 * long.
 * <li>{@code WAIT_LOG|<timeoutMs>} - blocks until <em>any</em>
 * {@code LOG_MESSAGE} (§10.1) is received, or the timeout elapses.
 * <li>{@code WAIT_LOG|<timeoutMs>|<marker>} - like the above, but only a
 * matching marker satisfies it (same escaping as any other string field, see
 * {@link TextCommandFormat}).
 * <li>{@code SYNC|<localDir>|<volume: SD|INTERNAL|PSRAM>|<devicePath>|<mode:
 * PC_MASTER|DEVICE_MASTER|MERGE>} - recursively syncs a local folder against
 * device storage (see {@link FolderSync}), issuing whatever
 * FILE_LIST/DOWNLOAD/UPLOAD/DELETE commands the chosen mode needs rather than
 * being one wire command itself. The sync manifest (MERGE mode's
 * change-tracking state) lives alongside {@code localDir} as a sibling
 * {@code <localDir-name>.bshmisync-manifest} file.
 * <li>{@code ICONSPEC|<name>|<spec>} - generates an image from the icon spec,
 * converts it to binary B/W format, and stores it in {@link IconSpecCache}
 * under {@code name}. A later command can reference it from a {@code BYTES}
 * field as {@code #name} (mirroring {@code @<file>}, see
 * {@link TextCommandFormat}). Requires the {@code common-java-utils} library on
 * the classpath.
 * <li>{@code OTA|@<firmware_file>} - completes the whole install/confirm cycle
 * (doc/PROTOCOL.md §16) in one command: reads the given {@code firmware.bin},
 * SHA-256-hashes it, installs it via {@link HmiDevice#otaInstall} with
 * {@code APPLY_NOW} set, waits out a settle delay and reconnects
 * ({@link HmiDevice#reconnect}) once the device reboots into the new image,
 * re-handshakes (access level resets on every new connection/boot, §5.3), and
 * sends {@code OTA_CONFIRM} - or, if the device is found still running the
 * previous firmware (an early/crash-triggered rollback beat the reconnect to
 * it), skips confirming since there is nothing new to confirm. Either way,
 * draws a compact status banner on the device's own screen (device name,
 * transport, and the version now actually running) before returning. The
 * {@code @} is the same {@code BYTES}-field file convention as everywhere else
 * (see {@link TextCommandFormat#parseBytesToken}), not OTA-specific syntax. Can
 * take minutes for a multi-hundred-KB image over a slow transport - the
 * transfer timeout scales with image size (see {@code OTA_TIMEOUT_FLOOR_MS}),
 * and a PC-side percentage is printed as it sends (see
 * {@link cz.bliksoft.hmieink.protocol.TransferProgressListener}) - not a
 * protocol-level chunk acknowledgment, just client-side transfer
 * instrumentation. A caller wanting more manual control (e.g. its own health
 * check before confirming) can still send the raw
 * {@code OTA_INSTALL}/{@code OTA_APPLY}/{@code OTA_CONFIRM}/{@code OTA_ROLLBACK}
 * commands directly instead of this convenience pseudo-command.
 * </ul>
 *
 * A timed-out {@code WAIT_LOG} throws {@link IOException}, the same as an
 * ordinary command's {@code NACK}/timeout would - a script waiting on a
 * synchronization point that never arrives is a real error, not something to
 * silently continue past. {@code SYNC} likewise throws if it leaves any
 * unresolved conflicts (MERGE mode only) rather than silently continuing with
 * some paths left unsynced.
 */
public final class ScriptRunner {

	private final HmiDevice device;
	private final PrintStream out;
	private final String adminPin;
	private final String usagePin;

	public ScriptRunner(HmiDevice device) {
		this(device, System.out);
	}

	public ScriptRunner(HmiDevice device, PrintStream out) {
		this(device, out, null, null);
	}

	/**
	 * @param adminPin used only to redo the handshake after {@code OTA}'s
	 *                 post-reboot reconnect (access level resets on every new
	 *                 connection/boot, doc/PROTOCOL.md §5.3) - the same PIN(s) the
	 *                 original connection authenticated with, e.g. {@code Cli}'s
	 *                 {@code -K}/{@code -k}. Not needed (may be {@code null}) if
	 *                 scripts never use {@code OTA} on a PIN-protected device.
	 */
	public ScriptRunner(HmiDevice device, PrintStream out, String adminPin, String usagePin) {
		this.device = device;
		this.out = out;
		this.adminPin = adminPin;
		this.usagePin = usagePin;
	}

	public void runFile(String path, char separator) throws IOException {
		for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
			runLine(line, separator);
		}
	}

	public void runStdin(char separator) throws IOException {
		runStream(System.in, separator);
	}

	public void runStream(InputStream in, char separator) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		String line;
		while ((line = reader.readLine()) != null) {
			runLine(line, separator);
		}
	}

	public void runLine(String line, char separator) throws IOException {
		String trimmed = line.trim();
		if (trimmed.isEmpty() || trimmed.startsWith("#")) {
			return;
		}
		List<String> tokens = TextCommandFormat.tokenize(line, separator);
		switch (tokens.get(0).toUpperCase(Locale.ROOT)) {
		case "SLEEP":
			runSleep(tokens);
			return;
		case "WAIT_LOG":
			runWaitLog(tokens);
			return;
		case "SYNC":
			runSync(tokens);
			return;
		case "ICONSPEC":
			runIconSpec(tokens, line, separator);
			return;
		case "OTA":
			runOta(tokens);
			return;
		default:
			Frame response = device.sendText(line, separator);
			out.println("-> " + line);
			out.println("<- " + device.describe(response.getCommandId(), response.getPayload(), separator));
		}
	}

	private void runSleep(List<String> tokens) throws IOException {
		if (tokens.size() != 2) {
			throw new IllegalArgumentException("SLEEP expects exactly one field: duration in ms, got " + tokens);
		}
		long durationMs = parseLong(tokens.get(1), "SLEEP");
		out.println("-> SLEEP " + durationMs + "ms (local, not sent to the device)");
		try {
			Thread.sleep(durationMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted during SLEEP", e);
		}
		out.println("<- done");
	}

	private void runWaitLog(List<String> tokens) throws IOException {
		if (tokens.size() != 2 && tokens.size() != 3) {
			throw new IllegalArgumentException(
					"WAIT_LOG expects timeoutMs[, marker] (local, not sent to the device), got " + tokens);
		}
		long timeoutMs = parseLong(tokens.get(1), "WAIT_LOG");
		String marker = tokens.size() == 3 ? tokens.get(2) : null;
		out.println("-> WAIT_LOG " + timeoutMs + "ms " + (marker != null ? "marker=" + marker : "(any)")
				+ " (local, not sent to the device)");
		if (marker != null) {
			device.getCommandClient().waitForLogMessage(marker.getBytes(StandardCharsets.UTF_8), timeoutMs);
		} else {
			device.getCommandClient().waitForLogMessage(timeoutMs);
		}
		out.println("<- received");
	}

	private void runSync(List<String> tokens) throws IOException {
		if (tokens.size() != 5) {
			throw new IllegalArgumentException("SYNC expects localDir|volume(SD|INTERNAL|PSRAM)|devicePath|"
					+ "mode(PC_MASTER|DEVICE_MASTER|MERGE) (local orchestration, not sent to the device as a "
					+ "single command), got " + tokens);
		}
		Path localDir = Paths.get(tokens.get(1));
		int volume = parseVolume(tokens.get(2));
		String devicePath = tokens.get(3);
		SyncMode mode = parseSyncMode(tokens.get(4));
		Path manifestFile = localDir.resolveSibling(localDir.getFileName() + ".bshmisync-manifest");
		out.println("-> SYNC " + localDir + " <-> VOLUME=" + tokens.get(2).toUpperCase(Locale.ROOT) + ":" + devicePath
				+ " (" + mode + ", local orchestration, not sent to the device as a single command)");
		SyncResult result = FolderSync.sync(localDir, device, volume, devicePath, mode, manifestFile);
		out.print(result);
		if (result.hasConflicts()) {
			throw new IOException(
					"SYNC completed with " + result.conflicted.size() + " unresolved conflict(s) - see output above");
		}
	}

	private static int parseVolume(String token) {
		switch (token.toUpperCase(Locale.ROOT)) {
		case "SD":
			return Volume.SD;
		case "INTERNAL":
			return Volume.INTERNAL;
		case "PSRAM":
			return Volume.PSRAM;
		default:
			throw new IllegalArgumentException("SYNC: unknown volume '" + token + "' (expected SD|INTERNAL|PSRAM)");
		}
	}

	private static SyncMode parseSyncMode(String token) {
		try {
			return SyncMode.valueOf(token.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(
					"SYNC: unknown mode '" + token + "' (expected PC_MASTER|DEVICE_MASTER|MERGE)", e);
		}
	}

	private void runIconSpec(List<String> tokens, String line, char separator) throws IOException {
		if (tokens.size() < 3) {
			throw new IllegalArgumentException("ICONSPEC expects name|spec (at least 2 fields), got " + tokens);
		}
		String name = tokens.get(1);
		// The spec is everything after the first separator following the name
		// Find the position of the first separator after "ICONSPEC|name"
		String spec;
		int nameEnd = tokens.get(0).length() + 1 + tokens.get(1).length();
		if (nameEnd < line.length() && line.charAt(nameEnd) == separator) {
			spec = line.substring(nameEnd + 1);
		} else {
			// Fallback: join remaining tokens with separator
			StringBuilder sb = new StringBuilder(tokens.get(2));
			for (int i = 3; i < tokens.size(); i++) {
				sb.append(separator).append(tokens.get(i));
			}
			spec = sb.toString();
		}
		out.println("-> ICONSPEC " + name + " | " + spec);
		if (!IconSpecCache.isAvailable()) {
			throw new IOException(
					"ICONSPEC requires the common-java-utils library (cz.bliksoft.java:common-java-utils) on the classpath");
		}
		try {
			byte[] epi = IconSpecCache.generateAndCache(name, spec);
			out.println("<- cached as #" + name + " (" + epi.length + " bytes)");
		} catch (UnsupportedOperationException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Floor for {@code OTA}'s transfer timeout, regardless of image size -
	 * handshake/ACK round trips and connection setup need some minimum budget even
	 * for a tiny image.
	 */
	private static final long OTA_TIMEOUT_FLOOR_MS = 60_000;

	/**
	 * Assumed worst-case transfer rate (bytes/ms) used to scale {@code OTA}'s
	 * timeout with image size - deliberately conservative (slower than 115200 baud
	 * Serial already tests fine at in {@code OtaManualCheck}, and BLE's per-packet
	 * ACK overhead can make it slower than Serial for large transfers) so a large
	 * image over a slow transport doesn't spuriously time out mid-transfer.
	 */
	private static final double OTA_ASSUMED_BYTES_PER_MS = 5.0;

	/**
	 * Mandatory quiet period before {@code OTA} touches the transport again after
	 * the install ACK - see {@link HmiDevice#reconnect}'s own doc for why this
	 * can't just be folded into retry backoff (Serial's DTR-reset-on-connect would
	 * race the device's own in-progress restart). Matches the already-proven
	 * {@code OtaManualCheck}/{@code SerialFrameTransport.DEFAULT_RESET_SETTLE_DELAY_MS}
	 * scale.
	 */
	private static final long OTA_REBOOT_SETTLE_MS = 13_000;

	/**
	 * Overall budget for {@code OTA}'s post-reboot reconnect, after the settle
	 * delay above.
	 */
	private static final long OTA_RECONNECT_TIMEOUT_MS = 60_000;

	/** Gap between reconnect attempts within {@link #OTA_RECONNECT_TIMEOUT_MS}. */
	private static final long OTA_RECONNECT_RETRY_INTERVAL_MS = 3_000;

	private void runOta(List<String> tokens) throws IOException {
		if (tokens.size() != 2) {
			throw new IllegalArgumentException("OTA expects exactly one field: @<path to firmware.bin>, got " + tokens);
		}
		byte[] image = TextCommandFormat.parseBytesToken(tokens.get(1));
		byte[] sha256 = sha256(image);
		long timeoutMs = Math.max(OTA_TIMEOUT_FLOOR_MS, (long) (image.length / OTA_ASSUMED_BYTES_PER_MS));
		long previousSlot = otaRunningSlot();
		out.println("-> OTA " + tokens.get(1) + " (" + image.length + " bytes, SHA-256, APPLY_NOW, timeout=" + timeoutMs
				+ "ms - local orchestration, not sent to the device as a single command)");
		long[] lastPercent = { -1 };
		device.otaInstall(image, OtaHashAlgo.SHA256, sha256, true, timeoutMs, (sent, total) -> {
			long percent = total == 0 ? 100 : sent * 100 / total;
			if (percent != lastPercent[0]) {
				lastPercent[0] = percent;
				out.print("\r   sending... " + percent + "% (" + sent + "/" + total + " bytes)");
				out.flush(); // PrintStream.print() doesn't auto-flush (only println() does) - without this,
								// every update sits buffered until the final println() below.
			}
		});
		out.println();
		out.println("<- staged, verified, and applying - device is rebooting into the new firmware");

		out.println("-> waiting " + OTA_REBOOT_SETTLE_MS + "ms for the device to finish rebooting, then "
				+ "reconnecting to confirm (up to " + OTA_RECONNECT_TIMEOUT_MS + "ms)...");
		device.reconnect(OTA_REBOOT_SETTLE_MS, OTA_RECONNECT_TIMEOUT_MS, OTA_RECONNECT_RETRY_INTERVAL_MS);
		HandshakeCapabilities capabilities = device.handshake(adminPin, usagePin);
		Map<String, Object> status = device.otaStatus();
		long newSlot = ((Number) status.get("RUNNING_SLOT")).longValue();
		boolean success = newSlot != previousSlot;
		if (success) {
			device.otaConfirm();
		}
		drawOtaResultMessage(capabilities, success);
		if (success) {
			out.println("<- reconnected and confirmed - update complete, rollback safety net cancelled");
		} else {
			out.println("<- reconnected, but the device is still on the previous firmware (slot " + newSlot + ", "
					+ status.get("RUNNING_VERSION") + ") - the new image never took effect, nothing to confirm");
		}
	}

	private long otaRunningSlot() throws IOException {
		return ((Number) device.otaStatus().get("RUNNING_SLOT")).longValue();
	}

	/**
	 * Draws a compact status banner on the device's own screen once {@code OTA} has
	 * (or hasn't) taken effect - requested directly, "serves as a sample and as a
	 * tool". Uses the handshake's own reported display width (falls back to an
	 * unbounded single line if unreported) rather than a hardcoded one, and a
	 * single {@code DRAW_TEXT} call with embedded {@code \n}s (doc/PROTOCOL.md
	 * §12.6: "An embedded U+000A (LF) always starts a new line regardless of WRAP")
	 * instead of several separate commands - the same code works unchanged on a
	 * smaller/differently-sized screen.
	 */
	private void drawOtaResultMessage(HandshakeCapabilities capabilities, boolean success) throws IOException {
		int width = Math.max(0, capabilities.getDisplayWidthPx());
		String transport = device.getCommandClient().getTransport().getClass().getSimpleName().replace("FrameTransport",
				"");
		String deviceName = capabilities.getDeviceName() != null ? capabilities.getDeviceName() : "device";
		String version = capabilities.getFirmwareVersion() != null ? capabilities.getFirmwareVersion()
				: "unknown version";
		String message = (success ? "OTA update complete" : "OTA rolled back") + "\n" + deviceName + " via " + transport
				+ "\n" + version;
		device.drawText(0, 0, width, FontId.EMBEDDED_CLASSIC, Color.BLACK, Color.WHITE, DrawMode.REPLACE,
				TextAlign.CENTER, true, WriteFlags.REFRESH_NOW | WriteFlags.REFRESH_FULL, message);
	}

	private static byte[] sha256(byte[] data) throws IOException {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("OTA: SHA-256 not available", e);
		}
	}

	private static long parseLong(String token, String commandName) {
		try {
			return Long.parseLong(token.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(commandName + ": not a valid duration/timeout in ms: " + token, e);
		}
	}
}
