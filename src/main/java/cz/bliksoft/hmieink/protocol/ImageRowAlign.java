package cz.bliksoft.hmieink.protocol;

/**
 * DRAW_IMAGE_ROW's ALIGN byte (doc/PROTOCOL.md §12.x) - meaningful only when WIDTH &gt; 0. BLOCK
 * auto-distributes leftover space (WIDTH minus the sum of every image's own width) evenly across
 * the gaps between images, ignoring SPACING; every other value uses SPACING as a fixed gap.
 * Mirrors firmware's {@code Protocol.h} {@code imageRowAlign} namespace - keep both in sync.
 */
public final class ImageRowAlign {

	private ImageRowAlign() {
	}

	public static final int LEFT = 0x00;
	public static final int CENTER = 0x01;
	public static final int RIGHT = 0x02;
	public static final int BLOCK = 0x03;
}
