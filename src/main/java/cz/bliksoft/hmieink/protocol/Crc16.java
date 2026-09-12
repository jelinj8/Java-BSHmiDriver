package cz.bliksoft.hmieink.protocol;

/**
 * CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflect, no xorout).
 *
 * Reference vector (shared with the firmware implementation to guarantee both
 * sides agree): ASCII "123456789" (9 bytes) -&gt; 0x29B1.
 */
public final class Crc16 {

	private static final int POLY = 0x1021;
	private static final int INIT = 0xFFFF;

	private Crc16() {
	}

	public static int compute(byte[] data) {
		return compute(data, 0, data.length);
	}

	public static int compute(byte[] data, int offset, int length) {
		int crc = INIT;
		for (int i = offset; i < offset + length; i++) {
			crc ^= (data[i] & 0xFF) << 8;
			for (int bit = 0; bit < 8; bit++) {
				if ((crc & 0x8000) != 0) {
					crc = ((crc << 1) ^ POLY) & 0xFFFF;
				} else {
					crc = (crc << 1) & 0xFFFF;
				}
			}
		}
		return crc & 0xFFFF;
	}
}
