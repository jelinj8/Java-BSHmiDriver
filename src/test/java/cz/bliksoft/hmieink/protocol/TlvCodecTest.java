package cz.bliksoft.hmieink.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TlvCodecTest {

	@Test
	void decodesEmptyPayloadAsEmptyList() {
		assertTrue(TlvCodec.decode(new byte[0]).isEmpty());
	}

	@Test
	void roundTripsSeveralEntryTypes() {
		byte[] payload = new TlvCodec.Builder().u8(0x01, 7).u16LE(0x02, 400).u32LE(0x03, 123456789L)
				.utf8(0x04, "CrowPanel-4.2-EPD").build();

		List<Tlv> entries = TlvCodec.decode(payload);

		assertEquals(4, entries.size());
		assertEquals(7, entries.get(0).asU8());
		assertEquals(400, entries.get(1).asU16LE());
		assertEquals(123456789L, entries.get(2).asU32LE());
		assertEquals("CrowPanel-4.2-EPD", entries.get(3).asUtf8());
	}

	@Test
	void truncatedTrailingEntryIsDroppedNotThrown() {
		byte[] good = new TlvCodec.Builder().u8(0x01, 1).build();
		// append a header claiming a 10-byte value but supply none
		byte[] payload = new byte[good.length + 2];
		System.arraycopy(good, 0, payload, 0, good.length);
		payload[good.length] = 0x02; // type
		payload[good.length + 1] = 10; // declared length, but 0 bytes actually follow

		List<Tlv> entries = TlvCodec.decode(payload);

		assertEquals(1, entries.size());
		assertEquals(0x01, entries.get(0).getType());
	}

	@Test
	void unknownTypeIsPreservedAsAnOpaqueEntry() {
		byte[] payload = new TlvCodec.Builder().raw(0xEE, new byte[] { 9, 8, 7 }).build();

		List<Tlv> entries = TlvCodec.decode(payload);

		assertEquals(1, entries.size());
		assertEquals(0xEE, entries.get(0).getType());
		assertEquals(3, entries.get(0).getValue().length);
	}
}
