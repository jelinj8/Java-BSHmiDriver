package cz.bliksoft.hmieink.protocol;

/**
 * FILL_IMAGE's TILE_MODE byte (doc/PROTOCOL.md §12.x) - the axis NOT named keeps the image's own
 * natural size (a single row/column) rather than tiling. Mirrors firmware's {@code Protocol.h}
 * {@code fillTileMode} namespace - keep both in sync.
 */
public final class FillTileMode {

	private FillTileMode() {
	}

	public static final int HORIZONTAL = 0x00;
	public static final int VERTICAL = 0x01;
	public static final int BOTH = 0x02;
}
