package cz.bliksoft.hmieink.protocol;

/**
 * DRAW_TEXT's FONT_ID byte (doc/PROTOCOL.md §12.6). Mirrors firmware's
 * {@code Protocol.h}/ {@code EmbeddedFont.h}/{@code CustomFont.h} font-id
 * values - keep both in sync.
 */
public final class FontId {

	private FontId() {
	}

	public static final int EMBEDDED_CLASSIC = 0x00;
	public static final int EMBEDDED_LARGE = 0x01;

	/**
	 * Folder-driven proportional font (doc/PROTOCOL.md §12.6.1), set up via
	 * {@link CommandId#SET_CUSTOM_FONT_FOLDER}. Unlike the two embedded fonts, TEXT
	 * for this font is a raw single-byte codepage, not UTF-8 - see
	 * {@link HmiDevice#drawTextCustom}.
	 */
	public static final int CUSTOM = 0xFF;
}
