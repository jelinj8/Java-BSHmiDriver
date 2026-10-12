package cz.bliksoft.hmieink.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.Encoding;
import cz.bliksoft.hmieink.protocol.RlePackBits;
import cz.bliksoft.hmieink.protocol.schema.CommandSchema;
import cz.bliksoft.hmieink.protocol.schema.PayloadCodec;

class ScreenImageCodecTest {

	/** 10x2: row 0 has pixels 0 and 9 black, row 1 is all black (bit=1=BLACK, MSB-first). */
	private static final byte[] PIXELS = { (byte) 0x80, 0x40, (byte) 0xFF, (byte) 0xC0 };

	@Test
	void decodesRawAndRleAlike() {
		assertPattern(ScreenImageCodec.decode(Encoding.RAW, 10, 2, PIXELS));
		assertPattern(ScreenImageCodec.decode(Encoding.RLE_PACKBITS, 10, 2, RlePackBits.encode(PIXELS)));
	}

	@Test
	void decodesAScreenDataResponse() {
		byte[] rle = RlePackBits.encode(PIXELS);
		ByteBuffer payload = ByteBuffer.allocate(17 + rle.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Encoding.RLE_PACKBITS).putShort((short) 0).putShort((short) 0).putShort((short) 10)
				.putShort((short) 2).putInt(PIXELS.length).putInt(rle.length).put(rle);
		Map<String, Object> fields = PayloadCodec.decode(CommandSchema.byId(CommandId.SCREEN_DATA), payload.array());
		assertPattern(ScreenImageCodec.decode(fields));
	}

	@Test
	void rejectsDataNotMatchingTheSize() {
		assertThrows(IllegalArgumentException.class, () -> ScreenImageCodec.decode(Encoding.RAW, 10, 3, PIXELS));
	}

	private static void assertPattern(BufferedImage image) {
		assertEquals(10, image.getWidth());
		assertEquals(2, image.getHeight());
		for (int x = 0; x < 10; x++) {
			assertEquals(x == 0 || x == 9, isBlack(image, x, 0), "row 0 pixel " + x);
			assertEquals(true, isBlack(image, x, 1), "row 1 pixel " + x);
		}
	}

	private static boolean isBlack(BufferedImage image, int x, int y) {
		return (image.getRGB(x, y) & 0xFFFFFF) == 0;
	}
}
