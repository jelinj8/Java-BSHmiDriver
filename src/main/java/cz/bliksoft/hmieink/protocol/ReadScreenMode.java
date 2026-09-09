package cz.bliksoft.hmieink.protocol;

/**
 * READ_SCREEN's MODE byte (doc/PROTOCOL.md §8). Mirrors firmware's {@code Protocol.h}
 * {@code readScreenMode} namespace - keep both in sync.
 */
public final class ReadScreenMode {

	private ReadScreenMode() {
	}

	public static final int FULL = 0x00;
	public static final int REGION = 0x01;
}
