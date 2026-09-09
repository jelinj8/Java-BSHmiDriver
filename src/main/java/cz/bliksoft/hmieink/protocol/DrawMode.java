package cz.bliksoft.hmieink.protocol;

/**
 * Local drawing primitives' compositing mode (doc/PROTOCOL.md §12.1). Mirrors firmware's
 * {@code Protocol.h} {@code drawMode} namespace - keep both in sync.
 */
public final class DrawMode {

	private DrawMode() {
	}

	public static final int REPLACE = 0x00;
	public static final int OR = 0x01;
	public static final int XOR = 0x02;
	public static final int AND = 0x03;
}
