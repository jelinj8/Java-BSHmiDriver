package cz.bliksoft.hmieink.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;

import org.junit.jupiter.api.Test;

class RlePackBitsTest {

	@Test
	void roundTripsAllZeros() {
		byte[] data = new byte[15000];
		roundTrip(data);
	}

	@Test
	void roundTripsAllOnes() {
		byte[] data = new byte[15000];
		java.util.Arrays.fill(data, (byte) 0xFF);
		roundTrip(data);
	}

	@Test
	void roundTripsRandomData() {
		byte[] data = new byte[15000];
		new Random(42).nextBytes(data);
		roundTrip(data);
	}

	@Test
	void roundTripsMixedRunsAndLiterals() {
		byte[] data = new byte[1000];
		Random rnd = new Random(7);
		int i = 0;
		while (i < data.length) {
			if (rnd.nextBoolean()) {
				int runLen = Math.min(1 + rnd.nextInt(200), data.length - i);
				byte v = (byte) rnd.nextInt(256);
				for (int j = 0; j < runLen; j++) {
					data[i + j] = v;
				}
				i += runLen;
			} else {
				data[i++] = (byte) rnd.nextInt(256);
			}
		}
		roundTrip(data);
	}

	@Test
	void encodesLongRunCompactly() {
		byte[] data = new byte[15000];
		byte[] encoded = RlePackBits.encode(data);
		// Repeat runs cap at 129 bytes/packet (2 wire bytes each): ceil(15000/129) * 2
		// = 234.
		int expected = (int) Math.ceil(data.length / 129.0) * 2;
		assertEquals(expected, encoded.length);
		assertArrayEquals(data, RlePackBits.decode(encoded, data.length));
	}

	@Test
	void decodeRejectsOverrun() {
		// control byte claims a 128-byte literal run but supplies none
		byte[] malformed = new byte[] { 127 };
		assertThrows(IllegalArgumentException.class, () -> RlePackBits.decode(malformed, 128));
	}

	@Test
	void decodeRejectsLengthMismatch() {
		byte[] encoded = RlePackBits.encode(new byte[10]);
		assertThrows(IllegalArgumentException.class, () -> RlePackBits.decode(encoded, 5));
	}

	private static void roundTrip(byte[] data) {
		byte[] encoded = RlePackBits.encode(data);
		byte[] decoded = RlePackBits.decode(encoded, data.length);
		assertArrayEquals(data, decoded);
	}
}
