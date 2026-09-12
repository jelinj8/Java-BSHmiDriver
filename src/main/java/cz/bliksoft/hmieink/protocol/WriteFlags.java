package cz.bliksoft.hmieink.protocol;

/**
 * The shared write-command FLAGS byte (doc/PROTOCOL.md §2.1), used by
 * FULL_IMAGE_TRANSFER, PARTIAL_IMAGE_TRANSFER, and all local drawing
 * primitives. Mirrors firmware's {@code Protocol.h} {@code writeFlags}
 * namespace - keep both in sync.
 */
public final class WriteFlags {

	private WriteFlags() {
	}

	public static final int REFRESH_NOW = 1;
	public static final int REFRESH_FULL = 1 << 1;
}
