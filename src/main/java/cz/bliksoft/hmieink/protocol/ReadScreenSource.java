package cz.bliksoft.hmieink.protocol;

/**
 * READ_SCREEN's SOURCE byte (doc/PROTOCOL.md §8). Mirrors firmware's
 * {@code Protocol.h} {@code readScreenSource} namespace - keep both in sync.
 */
public final class ReadScreenSource {

	private ReadScreenSource() {
	}

	/** Content last physically presented to the e-ink panel. */
	public static final int PANEL = 0x00;

	/**
	 * Current in-memory buffer, including any writes still deferred via
	 * FLAGS.REFRESH_NOW=0.
	 */
	public static final int WORKING_BUFFER = 0x01;
}
