package cz.bliksoft.hmieink.protocol.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * What one {@link FolderSync#sync} run did, per relative path. Every list holds
 * relative paths (forward-slash-separated); {@link #toString} renders a short
 * human-readable summary suitable for CLI output.
 */
public final class SyncResult {

	public final List<String> uploaded = new ArrayList<>();
	public final List<String> downloaded = new ArrayList<>();
	public final List<String> deletedLocal = new ArrayList<>();
	public final List<String> deletedRemote = new ArrayList<>();
	public final List<String> unchanged = new ArrayList<>();

	/**
	 * MERGE mode only: paths changed differently on both sides since the last sync
	 * - left untouched.
	 */
	public final List<String> conflicted = new ArrayList<>();

	/**
	 * Local subdirectories that were not synced because the remote side doesn't
	 * support real subdirectories (VOLUME=PSRAM, doc/PROTOCOL.md §14).
	 */
	public final List<String> skippedLocalDirectories = new ArrayList<>();

	public boolean hasConflicts() {
		return !conflicted.isEmpty();
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		appendSection(sb, "uploaded", uploaded);
		appendSection(sb, "downloaded", downloaded);
		appendSection(sb, "deleted (local)", deletedLocal);
		appendSection(sb, "deleted (remote)", deletedRemote);
		appendSection(sb, "conflicted", conflicted);
		appendSection(sb, "skipped local directories (remote has no subdirectories)", skippedLocalDirectories);
		if (sb.length() == 0) {
			return "no changes";
		}
		return sb.toString();
	}

	private static void appendSection(StringBuilder sb, String label, List<String> paths) {
		if (paths.isEmpty()) {
			return;
		}
		sb.append(label).append(" (").append(paths.size()).append("):\n");
		for (String path : paths) {
			sb.append("  ").append(path).append('\n');
		}
	}
}
