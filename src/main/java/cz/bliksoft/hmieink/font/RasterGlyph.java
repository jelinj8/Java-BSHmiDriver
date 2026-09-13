package cz.bliksoft.hmieink.font;

/**
 * One rasterized, already-thresholded-to-1bpp glyph - the in-memory counterpart
 * of a {@code .gly} file's own fields (doc/PROTOCOL.md §12.6.1):
 * {@link #width}/{@link #height} in pixels, {@link #packedBitmap} row-major
 * MSB-first with bit=1=ink, {@code bytesPerRow=(width+7)/8}.
 */
public final class RasterGlyph {

	public final int width;
	public final int height;
	public final byte[] packedBitmap;

	public RasterGlyph(int width, int height, byte[] packedBitmap) {
		this.width = width;
		this.height = height;
		this.packedBitmap = packedBitmap;
	}
}
