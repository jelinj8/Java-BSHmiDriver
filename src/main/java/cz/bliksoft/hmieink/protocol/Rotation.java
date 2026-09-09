package cz.bliksoft.hmieink.protocol;

/**
 * SET_ORIENTATION's ROTATION byte (doc/PROTOCOL.md §12.12) - clockwise rotation of the logical
 * canvas relative to the physical panel; 90/270 swap the logical canvas's width/height. Mirrors
 * firmware's {@code Protocol.h} {@code orientation} namespace - keep both in sync.
 */
public final class Rotation {

	private Rotation() {
	}

	public static final int ROTATE_0 = 0x00;
	public static final int ROTATE_90 = 0x01;
	public static final int ROTATE_180 = 0x02;
	public static final int ROTATE_270 = 0x03;
}
