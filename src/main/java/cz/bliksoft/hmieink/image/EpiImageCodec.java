package cz.bliksoft.hmieink.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import cz.bliksoft.hmieink.protocol.RlePackBits;

/**
 * Encoder/decoder for the {@code .epi} image file format (doc/PROTOCOL.md
 * §12.7) - referenced by DRAW_IMAGE (uploaded to a volume via FILE_UPLOAD,
 * §14.3). Converts to and from a plain {@link BufferedImage}: {@link #encode}
 * thresholds each pixel's luminance to 1bpp (bit=1=BLACK, matching §6's wire
 * polarity) and, optionally, derives a transparency mask from the alpha
 * channel; {@link #decode} reconstructs a {@code TYPE_INT_ARGB} image from the
 * packed streams. Both directions always RLE-encode the packed streams (reusing
 * {@link RlePackBits}) - "any conformant encoder is valid" per §6, and RLE is a
 * safe default for typical icon/image content.
 */
public final class EpiImageCodec {

	private static final byte[] MAGIC = { 'E', 'P', 'I', '1' };
	private static final int FORMAT_VERSION = 0x01;
	@SuppressWarnings("unused")
	private static final int ENCODING_RAW = 0x00;
	private static final int ENCODING_RLE = 0x01;
	private static final int FLAG_HAS_MASK = 0x01;
	private static final int HEADER_SIZE = 19;

	/**
	 * A pixel's average RGB value strictly below this (out of 255) becomes BLACK.
	 */
	private static final int LUMINANCE_THRESHOLD = 128;

	/**
	 * An alpha value strictly below this (out of 255) becomes transparent (mask bit
	 * 0).
	 */
	private static final int ALPHA_THRESHOLD = 128;

	private EpiImageCodec() {
	}

	/** Encodes without a transparency mask - every pixel is opaque. */
	public static byte[] encode(BufferedImage image) {
		return encode(image, false);
	}

	public static byte[] encode(BufferedImage image, boolean includeMask) {
		int width = image.getWidth();
		int height = image.getHeight();
		byte[] colorData = packBits(width, height, (x, y) -> luminance(image.getRGB(x, y)) < LUMINANCE_THRESHOLD);
		byte[] colorEncoded = RlePackBits.encode(colorData);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(MAGIC, 0, MAGIC.length);
		out.write(FORMAT_VERSION);
		writeU16LE(out, width);
		writeU16LE(out, height);
		out.write(ENCODING_RLE);
		out.write(includeMask ? FLAG_HAS_MASK : 0x00);
		writeU32LE(out, colorData.length);
		writeU32LE(out, colorEncoded.length);
		out.write(colorEncoded, 0, colorEncoded.length);

		if (includeMask) {
			byte[] maskData = packBits(width, height, (x, y) -> alpha(image.getRGB(x, y)) >= ALPHA_THRESHOLD);
			byte[] maskEncoded = RlePackBits.encode(maskData);
			writeU32LE(out, maskData.length);
			writeU32LE(out, maskEncoded.length);
			out.write(maskEncoded, 0, maskEncoded.length);
		}
		return out.toByteArray();
	}

	public static BufferedImage decode(byte[] epi) {
		if (epi.length < HEADER_SIZE || epi[0] != MAGIC[0] || epi[1] != MAGIC[1] || epi[2] != MAGIC[2]
				|| epi[3] != MAGIC[3] || (epi[4] & 0xFF) != FORMAT_VERSION) {
			throw new IllegalArgumentException("not a valid .epi file (bad magic/version)");
		}
		int width = readU16LE(epi, 5);
		int height = readU16LE(epi, 7);
		int encoding = epi[9] & 0xFF;
		boolean hasMask = (epi[10] & FLAG_HAS_MASK) != 0;
		int colorEncodedLen = (int) readU32LE(epi, 15);
		int colorDecodedLen = (int) readU32LE(epi, 11);

		int pos = HEADER_SIZE;
		byte[] colorData = decodeStream(epi, pos, colorEncodedLen, colorDecodedLen, encoding);
		pos += colorEncodedLen;

		byte[] maskData = null;
		if (hasMask) {
			int maskDecodedLen = (int) readU32LE(epi, pos);
			int maskEncodedLen = (int) readU32LE(epi, pos + 4);
			pos += 8;
			maskData = decodeStream(epi, pos, maskEncodedLen, maskDecodedLen, encoding);
		}

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		int bytesPerRow = (width + 7) / 8;
		byte[] finalMaskData = maskData;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean black = getBit(colorData, bytesPerRow, x, y);
				boolean opaque = finalMaskData == null || getBit(finalMaskData, bytesPerRow, x, y);
				int rgb = black ? 0x000000 : 0xFFFFFF;
				int argb = (opaque ? 0xFF000000 : 0x00000000) | rgb;
				image.setRGB(x, y, argb);
			}
		}
		return image;
	}

	private interface PixelPredicate {
		boolean test(int x, int y);
	}

	private static byte[] packBits(int width, int height, PixelPredicate isSet) {
		int bytesPerRow = (width + 7) / 8;
		byte[] out = new byte[bytesPerRow * height];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				if (isSet.test(x, y)) {
					out[y * bytesPerRow + x / 8] |= (byte) (0x80 >> (x % 8));
				}
			}
		}
		return out;
	}

	private static byte[] decodeStream(byte[] data, int offset, int length, int decodedLen, int encoding) {
		byte[] encoded = new byte[length];
		System.arraycopy(data, offset, encoded, 0, length);
		return encoding == ENCODING_RLE ? RlePackBits.decode(encoded, decodedLen) : encoded;
	}

	private static boolean getBit(byte[] data, int bytesPerRow, int x, int y) {
		return (data[y * bytesPerRow + x / 8] & (0x80 >> (x % 8))) != 0;
	}

	private static int luminance(int argb) {
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		return (r + g + b) / 3;
	}

	private static int alpha(int argb) {
		return (argb >>> 24) & 0xFF;
	}

	private static void writeU16LE(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
	}

	private static void writeU32LE(ByteArrayOutputStream out, long value) {
		out.write((int) (value & 0xFF));
		out.write((int) ((value >> 8) & 0xFF));
		out.write((int) ((value >> 16) & 0xFF));
		out.write((int) ((value >> 24) & 0xFF));
	}

	private static int readU16LE(byte[] data, int offset) {
		return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
	}

	private static long readU32LE(byte[] data, int offset) {
		return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8) | ((data[offset + 2] & 0xFFL) << 16)
				| ((data[offset + 3] & 0xFFL) << 24);
	}
}
