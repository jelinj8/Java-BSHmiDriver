package cz.bliksoft.hmieink.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class Crc16Test {

	@Test
	void checkVector_123456789() {
		byte[] data = "123456789".getBytes(StandardCharsets.US_ASCII);
		assertEquals(0x29B1, Crc16.compute(data));
	}

	@Test
	void emptyInputIsInitValue() {
		assertEquals(0xFFFF, Crc16.compute(new byte[0]));
	}

	@Test
	void offsetAndLengthAreRespected() {
		byte[] padded = "XX123456789YY".getBytes(StandardCharsets.US_ASCII);
		assertEquals(0x29B1, Crc16.compute(padded, 2, 9));
	}
}
