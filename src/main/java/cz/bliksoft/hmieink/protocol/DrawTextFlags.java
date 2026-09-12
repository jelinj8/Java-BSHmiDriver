package cz.bliksoft.hmieink.protocol;

/**
 * DRAW_TEXT-specific FLAGS bits (doc/PROTOCOL.md §12.6) - bits 0-1 remain the
 * shared {@link WriteFlags} (§2.1) meaning; DRAW_TEXT extends the reserved bits
 * with its own. Mirrors firmware's {@code Protocol.h} {@code drawTextFlags}
 * namespace - keep both in sync.
 */
public final class DrawTextFlags {

	private DrawTextFlags() {
	}

	/**
	 * TEXT is a UTF-8 path on VOLUME=PSRAM rather than literal text - the file's
	 * own content, read fresh every time this command runs, becomes the text to
	 * draw.
	 */
	public static final int TEXT_IS_PATH = 1 << 2;
}
