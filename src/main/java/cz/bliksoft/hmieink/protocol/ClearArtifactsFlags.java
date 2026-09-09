package cz.bliksoft.hmieink.protocol;

/**
 * CLEAR_ARTIFACTS's FLAGS byte (doc/PROTOCOL.md §9). Mirrors firmware's {@code Protocol.h}
 * {@code clearArtifactsFlags} namespace - keep both in sync.
 */
public final class ClearArtifactsFlags {

	private ClearArtifactsFlags() {
	}

	/** Re-flip the current working buffer to the panel once cycling finishes (0 = leave blank). */
	public static final int RESTORE_CONTENT = 1;
}
