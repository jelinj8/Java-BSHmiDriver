package cz.bliksoft.hmieink.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class FrameTest {

	@Test
	void roundTripsEmptyPayload() {
		Frame frame = new Frame(0x0001, 0, new byte[0]);
		Frame decoded = Frame.decode(frame.encode());
		assertEquals(Frame.VERSION, decoded.getVersion());
		assertEquals(0x0001, decoded.getCommandId());
		assertEquals(0, decoded.getSeq());
		assertArrayEquals(new byte[0], decoded.getPayload());
	}

	@Test
	void roundTripsWithPayload() {
		byte[] payload = "hello crowpanel".getBytes(StandardCharsets.UTF_8);
		Frame frame = new Frame(0x0304, 200, payload);
		byte[] encoded = frame.encode();

		assertEquals(Frame.HEADER_SIZE + payload.length + Frame.CRC_SIZE, encoded.length);
		assertEquals((byte) Frame.MAGIC, encoded[0]);

		Frame decoded = Frame.decode(encoded);
		assertEquals(0x0304, decoded.getCommandId());
		assertEquals(200, decoded.getSeq());
		assertArrayEquals(payload, decoded.getPayload());
	}

	@Test
	void rejectsBadMagic() {
		byte[] encoded = new Frame(0x0001, 0, new byte[0]).encode();
		encoded[0] = 0x00;
		assertThrows(FrameException.class, () -> Frame.decode(encoded));
	}

	@Test
	void rejectsCorruptedCrc() {
		byte[] encoded = new Frame(0x0001, 0, new byte[] { 1, 2, 3 }).encode();
		encoded[encoded.length - 1] ^= 0xFF;
		assertThrows(FrameException.class, () -> Frame.decode(encoded));
	}

	@Test
	void rejectsTruncatedFrame() {
		byte[] encoded = new Frame(0x0001, 0, new byte[] { 1, 2, 3 }).encode();
		byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 1);
		assertThrows(FrameException.class, () -> Frame.decode(truncated));
	}

	@Test
	void rejectsPayloadLengthMismatch() {
		byte[] encoded = new Frame(0x0001, 0, new byte[] { 1, 2, 3 }).encode();
		// Corrupt PAYLOAD_LEN (offset 5, u32 LE) to claim a larger payload than is
		// present.
		encoded[5] = 100;
		assertThrows(FrameException.class, () -> Frame.decode(encoded));
	}

	@Test
	void rejectsOutOfRangeConstructorArgs() {
		assertThrows(IllegalArgumentException.class, () -> new Frame(-1, 0, null));
		assertThrows(IllegalArgumentException.class, () -> new Frame(0x10000, 0, null));
		assertThrows(IllegalArgumentException.class, () -> new Frame(0, -1, null));
		assertThrows(IllegalArgumentException.class, () -> new Frame(0, 256, null));
	}

	@Test
	void nullPayloadBecomesEmptyArray() {
		Frame frame = new Frame(0x0001, 0, null);
		assertArrayEquals(new byte[0], frame.getPayload());
	}
}
