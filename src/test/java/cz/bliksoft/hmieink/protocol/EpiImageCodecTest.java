package cz.bliksoft.hmieink.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class EpiImageCodecTest {

	@Test
	void roundTripsWithoutMask() {
		BufferedImage image = checkerboard(20, 15, false);
		BufferedImage decoded = EpiImageCodec.decode(EpiImageCodec.encode(image));
		assertSameBlackWhitePattern(image, decoded, false);
	}

	@Test
	void roundTripsWithMask() {
		BufferedImage image = checkerboard(20, 15, true);
		BufferedImage decoded = EpiImageCodec.decode(EpiImageCodec.encode(image, true));
		assertSameBlackWhitePattern(image, decoded, true);
	}

	@Test
	void encodedFileStartsWithExpectedMagicAndDimensions() {
		BufferedImage image = checkerboard(4, 3, false);
		byte[] epi = EpiImageCodec.encode(image);
		assertEquals('E', epi[0]);
		assertEquals('P', epi[1]);
		assertEquals('I', epi[2]);
		assertEquals('1', epi[3]);
		assertEquals(0x01, epi[4]); // FORMAT_VERSION
		assertEquals(4, (epi[5] & 0xFF) | ((epi[6] & 0xFF) << 8)); // WIDTH
		assertEquals(3, (epi[7] & 0xFF) | ((epi[8] & 0xFF) << 8)); // HEIGHT
	}

	@Test
	void decodeRejectsBadMagic() {
		byte[] bogus = new byte[19];
		assertThrows(IllegalArgumentException.class, () -> EpiImageCodec.decode(bogus));
	}

	@Test
	void singlePixelImageRoundTrips() {
		BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, 0xFF000000); // opaque black
		BufferedImage decoded = EpiImageCodec.decode(EpiImageCodec.encode(image));
		assertEquals(1, decoded.getWidth());
		assertEquals(1, decoded.getHeight());
		assertEquals(0xFF000000, decoded.getRGB(0, 0));
	}

	/**
	 * A checkerboard where even (x+y) is opaque black and odd is either white or
	 * transparent.
	 */
	private static BufferedImage checkerboard(int width, int height, boolean oddIsTransparent) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean black = (x + y) % 2 == 0;
				int argb;
				if (black) {
					argb = 0xFF000000;
				} else if (oddIsTransparent) {
					argb = 0x00FFFFFF;
				} else {
					argb = 0xFFFFFFFF;
				}
				image.setRGB(x, y, argb);
			}
		}
		return image;
	}

	private static void assertSameBlackWhitePattern(BufferedImage expected, BufferedImage actual, boolean hasMask) {
		assertEquals(expected.getWidth(), actual.getWidth());
		assertEquals(expected.getHeight(), actual.getHeight());
		for (int y = 0; y < expected.getHeight(); y++) {
			for (int x = 0; x < expected.getWidth(); x++) {
				boolean expectedBlack = (x + y) % 2 == 0;
				int actualArgb = actual.getRGB(x, y);
				boolean actualOpaque = ((actualArgb >>> 24) & 0xFF) >= 128;
				if (hasMask && !expectedBlack) {
					assertEquals(false, actualOpaque, "pixel (" + x + "," + y + ") should be transparent");
				} else {
					boolean actualBlack = (actualArgb & 0xFFFFFF) == 0x000000;
					assertEquals(expectedBlack, actualBlack, "pixel (" + x + "," + y + ") color mismatch");
				}
			}
		}
	}
}
