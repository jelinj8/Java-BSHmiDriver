package cz.bliksoft.hmieink.protocol;

/**
 * The image/screen ENCODING byte (doc/PROTOCOL.md §6), shared by
 * FULL_IMAGE_TRANSFER, PARTIAL_IMAGE_TRANSFER, SCREEN_DATA, and the
 * {@code .epi} file format's own header (§12.7). Firmware itself has no
 * equivalent named constant for this byte (just raw literals wherever it's
 * checked) - this class exists purely so PC-side code (the command schema in
 * particular) has one.
 */
public final class Encoding {

	private Encoding() {
	}

	public static final int RAW = 0x00;
	public static final int RLE_PACKBITS = 0x01;
}
