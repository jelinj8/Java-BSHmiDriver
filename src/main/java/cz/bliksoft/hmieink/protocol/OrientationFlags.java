package cz.bliksoft.hmieink.protocol;

/**
 * SET_ORIENTATION's FLAGS byte (doc/PROTOCOL.md §12.12) - mirroring, applied in
 * logical space before rotation. Mirrors firmware's {@code Protocol.h}
 * {@code orientationFlags} namespace - keep both in sync.
 */
public final class OrientationFlags {

	private OrientationFlags() {
	}

	public static final int MIRROR_H = 1;
	public static final int MIRROR_V = 1 << 1;
}
