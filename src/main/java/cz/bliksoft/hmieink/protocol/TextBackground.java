package cz.bliksoft.hmieink.protocol;

/**
 * DRAW_TEXT's BACKGROUND byte (doc/PROTOCOL.md §12.6). OPAQUE fills every
 * non-ink glyph pixel with the opposite of COLOR - "inverted" text is simply
 * {@link Color#WHITE} with an OPAQUE background, no separate concept needed.
 * Mirrors firmware's {@code Protocol.h} {@code textBackground} namespace - keep
 * both in sync.
 */
public final class TextBackground {

	private TextBackground() {
	}

	public static final int TRANSPARENT = 0x00;
	public static final int OPAQUE = 0x01;
}
