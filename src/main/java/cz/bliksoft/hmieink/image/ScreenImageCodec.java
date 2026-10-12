package cz.bliksoft.hmieink.image;

import java.awt.image.BufferedImage;
import java.util.Map;

import cz.bliksoft.hmieink.protocol.Encoding;
import cz.bliksoft.hmieink.protocol.RlePackBits;

/**
 * Decodes a {@code SCREEN_DATA} response (doc/PROTOCOL.md §8) - the panel's or
 * working buffer's packed 1bpp pixels, RAW or RLE - into a black-and-white
 * {@link BufferedImage}, e.g. to save a screenshot with
 * {@link javax.imageio.ImageIO}. Same wire polarity as §6 (bit=1=BLACK,
 * row-major, MSB-first).
 */
public final class ScreenImageCodec {

	private ScreenImageCodec() {
	}

	/**
	 * @param screenData the decoded {@code SCREEN_DATA} fields, as returned by
	 *                   {@link cz.bliksoft.hmieink.protocol.HmiDevice#readScreen}
	 */
	public static BufferedImage decode(Map<String, Object> screenData) {
		return decode(intField(screenData, "ENCODING"), intField(screenData, "WIDTH"), intField(screenData, "HEIGHT"),
				(byte[]) screenData.get("DATA"));
	}

	public static BufferedImage decode(int encoding, int width, int height, byte[] data) {
		int bytesPerRow = (width + 7) / 8;
		int decodedLen = bytesPerRow * height;
		byte[] pixels;
		if (encoding == Encoding.RLE_PACKBITS) {
			pixels = RlePackBits.decode(data, decodedLen);
		} else if (encoding == Encoding.RAW) {
			pixels = data;
		} else {
			throw new IllegalArgumentException("unknown SCREEN_DATA encoding " + encoding);
		}
		if (pixels.length != decodedLen) {
			throw new IllegalArgumentException(
					"SCREEN_DATA has " + pixels.length + " pixel bytes, " + width + "x" + height + " needs " + decodedLen);
		}
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean black = (pixels[y * bytesPerRow + x / 8] & (0x80 >> (x % 8))) != 0;
				image.setRGB(x, y, black ? 0xFF000000 : 0xFFFFFFFF);
			}
		}
		return image;
	}

	private static int intField(Map<String, Object> fields, String name) {
		Object value = fields.get(name);
		if (!(value instanceof Number)) {
			throw new IllegalArgumentException("SCREEN_DATA field " + name + " missing");
		}
		return ((Number) value).intValue();
	}
}
