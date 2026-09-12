package cz.bliksoft.hmieink.protocol;

/**
 * Local drawing primitives' 1bpp color value (doc/PROTOCOL.md §12.1) - matches
 * §6's wire polarity (bit=1=BLACK). Mirrors firmware's {@code Protocol.h}
 * {@code color} namespace - keep both in sync.
 */
public final class Color {

	private Color() {
	}

	public static final int WHITE = 0x00;
	public static final int BLACK = 0x01;
}
