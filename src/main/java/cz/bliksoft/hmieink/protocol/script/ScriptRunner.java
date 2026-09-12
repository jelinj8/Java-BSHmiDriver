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
import java.util.List;
import java.util.Locale;

import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.HmiDevice;
import cz.bliksoft.hmieink.protocol.Volume;
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
 * Two PC-local pseudo-commands control execution but are never sent to the
 * device - requested directly: "We need additional cmdline / filescript PC
 * local commands - wait for log message frame (e.g. finished macro, with a
 * timeout, with a specific text or any) and pause (by time) before sending
 * following commands. These wouldn't propagate to the device, just control
 * execution." Recognized by name before a line ever reaches
 * {@link HmiDevice#sendText} - neither name exists as a real {@code CommandId},
 * so there's no collision risk:
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

	public ScriptRunner(HmiDevice device) {
		this(device, System.out);
	}

	public ScriptRunner(HmiDevice device, PrintStream out) {
		this.device = device;
		this.out = out;
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

	private static long parseLong(String token, String commandName) {
		try {
			return Long.parseLong(token.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(commandName + ": not a valid duration/timeout in ms: " + token, e);
		}
	}
}
