package cz.bliksoft.hmieink.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HandshakeCapabilitiesTest {

	// Mirrors firmware's real buildHandshakeResponsePayload() (main.cpp) field-for-field, including
	// the values actually verified on real CrowPanel hardware (doc/PROTOCOL.md §21).
	private static byte[] realDeviceHandshakePayload() {
		return new TlvCodec.Builder().u8(HandshakeCapabilities.TLV_PROTOCOL_VERSION, 1)
				.u16LE(HandshakeCapabilities.TLV_DISPLAY_WIDTH_PX, 400)
				.u16LE(HandshakeCapabilities.TLV_DISPLAY_HEIGHT_PX, 300)
				.u8(HandshakeCapabilities.TLV_COLOR_DEPTH, 1)
				.u16LE(HandshakeCapabilities.TLV_MAX_CHUNK_SIZE, 244)
				.u32LE(HandshakeCapabilities.TLV_FEATURE_BITMASK, 0)
				.utf8(HandshakeCapabilities.TLV_DEVICE_MODEL, "CrowPanel-4.2-EPD")
				.utf8(HandshakeCapabilities.TLV_FIRMWARE_VERSION, "0.1.0-dev")
				.u8(HandshakeCapabilities.TLV_ACTIVE_TRANSPORT, 0x02)
				.utf8(HandshakeCapabilities.TLV_DEVICE_NAME, "CrowPanel-3851DC")
				.u16LE(HandshakeCapabilities.TLV_PIXEL_PITCH_X_UM, 212)
				.u16LE(HandshakeCapabilities.TLV_PIXEL_PITCH_Y_UM, 212).build();
	}

	@Test
	void parsesEveryFieldFirmwareActuallyReports() {
		HandshakeCapabilities caps = HandshakeCapabilities.parse(realDeviceHandshakePayload());

		assertEquals(1, caps.getProtocolVersion());
		assertEquals(400, caps.getDisplayWidthPx());
		assertEquals(300, caps.getDisplayHeightPx());
		assertEquals(1, caps.getColorDepth());
		assertEquals(244, caps.getMaxChunkSize());
		assertEquals(0L, caps.getFeatureBitmask());
		assertEquals("CrowPanel-4.2-EPD", caps.getDeviceModel());
		assertEquals("0.1.0-dev", caps.getFirmwareVersion());
		assertEquals(0x02, caps.getActiveTransport());
		assertEquals("CrowPanel-3851DC", caps.getDeviceName());
		assertEquals(212, caps.getPixelPitchXUm());
		assertEquals(212, caps.getPixelPitchYUm());
	}

	@Test
	void derivesDpiFromPixelPitch() {
		HandshakeCapabilities caps = HandshakeCapabilities.parse(realDeviceHandshakePayload());

		// 25400 um/inch / 212 um/px ~= 119.8 DPI, matching the spec sheet's ~120 DPI claim.
		assertEquals(119.81, caps.getDpiX(), 0.01);
		assertEquals(119.81, caps.getDpiY(), 0.01);
	}

	@Test
	void missingFieldsReturnSentinelsNotExceptions() {
		HandshakeCapabilities caps = HandshakeCapabilities.parse(new byte[0]);

		assertEquals(-1, caps.getProtocolVersion());
		assertEquals(-1, caps.getDisplayWidthPx());
		assertEquals(0L, caps.getFeatureBitmask());
		assertEquals(null, caps.getDeviceModel());
		assertTrue(Double.isNaN(caps.getDpiX()));
		assertEquals(0, caps.getAvailableGpioPins().length);
	}
}
