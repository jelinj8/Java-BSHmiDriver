package cz.bliksoft.hmieink.protocol;

/**
 * DRAW_TEXT's ALIGN byte (doc/PROTOCOL.md §12.6) - only meaningful when WIDTH &gt; 0. Mirrors
 * firmware's {@code EmbeddedFont.h} {@code TextAlign} enum - keep both in sync.
 */
public final class TextAlign {

	private TextAlign() {
	}

	public static final int LEFT = 0x00;
	public static final int CENTER = 0x01;
	public static final int RIGHT = 0x02;
}
