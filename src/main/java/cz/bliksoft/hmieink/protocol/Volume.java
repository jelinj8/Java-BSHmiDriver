package cz.bliksoft.hmieink.protocol;

/**
 * The storage VOLUME byte (doc/PROTOCOL.md §14, also used by DRAW_IMAGE §12.7).
 * Mirrors firmware's {@code Protocol.h} {@code volume} namespace - keep both in
 * sync.
 */
public final class Volume {

	private Volume() {
	}

	public static final int SD = 0x00;
	public static final int INTERNAL = 0x01;

	/**
	 * Flat, session-only in-memory volume, lost on reboot (doc/PROTOCOL.md §14).
	 */
	public static final int PSRAM = 0x02;
}
