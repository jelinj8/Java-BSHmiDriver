package cz.bliksoft.hmieink.protocol;

/**
 * SHIFT_REGION's DIRECTION byte (doc/PROTOCOL.md §12.9). Mirrors firmware's {@code Protocol.h}
 * {@code shiftDirection} namespace - keep both in sync.
 */
public final class ShiftDirection {

	private ShiftDirection() {
	}

	public static final int LEFT = 0x00;
	public static final int RIGHT = 0x01;
	public static final int UP = 0x02;
	public static final int DOWN = 0x03;
}
