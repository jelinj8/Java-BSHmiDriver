package cz.bliksoft.hmieink.protocol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typed view over a decoded HANDSHAKE_RESPONSE payload (doc/PROTOCOL.md §5.2).
 * Unknown/missing TLVs are tolerated - accessors return
 * {@code -1}/{@code null}/empty rather than throwing, per §5.1's "unknown TYPE
 * -> skip, never an error" (older or newer firmware may not report every field
 * this class knows about).
 */
public final class HandshakeCapabilities {

	// TLV TYPE values, doc/PROTOCOL.md §5.2.
	public static final int TLV_PROTOCOL_VERSION = 0x01;
	public static final int TLV_DISPLAY_WIDTH_PX = 0x02;
	public static final int TLV_DISPLAY_HEIGHT_PX = 0x03;
	public static final int TLV_COLOR_DEPTH = 0x04;
	public static final int TLV_MAX_CHUNK_SIZE = 0x05;
	public static final int TLV_FEATURE_BITMASK = 0x06;
	public static final int TLV_DEVICE_MODEL = 0x07;
	public static final int TLV_FIRMWARE_VERSION = 0x08;
	public static final int TLV_MAX_FULL_IMAGE_BYTES = 0x09;
	public static final int TLV_PARTIAL_REFRESH_GRANULARITY_X = 0x0A;
	public static final int TLV_PARTIAL_REFRESH_GRANULARITY_Y = 0x0B;
	public static final int TLV_ACTIVE_TRANSPORT = 0x0C;
	public static final int TLV_AVAILABLE_GPIO_PINS = 0x0D;
	public static final int TLV_LAST_WAKE_REASON = 0x0E;
	public static final int TLV_DEVICE_NAME = 0x0F;
	public static final int TLV_PIXEL_PITCH_X_UM = 0x10;
	public static final int TLV_PIXEL_PITCH_Y_UM = 0x11;
	public static final int TLV_GRANTED_LEVEL = 0x12;
	public static final int TLV_USAGE_PIN_REQUIRED = 0x13;
	public static final int TLV_ADMIN_PIN_REQUIRED = 0x14;

	private static final double UM_PER_INCH = 25400.0;

	private final Map<Integer, Tlv> byType = new HashMap<>();

	private HandshakeCapabilities(List<Tlv> entries) {
		for (Tlv tlv : entries) {
			byType.putIfAbsent(tlv.getType(), tlv); // first entry of a given type wins if ever repeated
		}
	}

	public static HandshakeCapabilities parse(byte[] handshakeResponsePayload) {
		return new HandshakeCapabilities(TlvCodec.decode(handshakeResponsePayload));
	}

	/**
	 * Direct access to a TLV by TYPE, for a field this class doesn't have a named
	 * getter for yet.
	 */
	public Optional<Tlv> get(int type) {
		return Optional.ofNullable(byType.get(type));
	}

	public int getProtocolVersion() {
		return get(TLV_PROTOCOL_VERSION).map(Tlv::asU8).orElse(-1);
	}

	public int getDisplayWidthPx() {
		return get(TLV_DISPLAY_WIDTH_PX).map(Tlv::asU16LE).orElse(-1);
	}

	public int getDisplayHeightPx() {
		return get(TLV_DISPLAY_HEIGHT_PX).map(Tlv::asU16LE).orElse(-1);
	}

	public int getColorDepth() {
		return get(TLV_COLOR_DEPTH).map(Tlv::asU8).orElse(-1);
	}

	public int getMaxChunkSize() {
		return get(TLV_MAX_CHUNK_SIZE).map(Tlv::asU16LE).orElse(-1);
	}

	public long getFeatureBitmask() {
		return get(TLV_FEATURE_BITMASK).map(Tlv::asU32LE).orElse(0L);
	}

	public String getDeviceModel() {
		return get(TLV_DEVICE_MODEL).map(Tlv::asUtf8).orElse(null);
	}

	public String getFirmwareVersion() {
		return get(TLV_FIRMWARE_VERSION).map(Tlv::asUtf8).orElse(null);
	}

	public long getMaxFullImageBytes() {
		return get(TLV_MAX_FULL_IMAGE_BYTES).map(Tlv::asU32LE).orElse(-1L);
	}

	public int getPartialRefreshGranularityX() {
		return get(TLV_PARTIAL_REFRESH_GRANULARITY_X).map(Tlv::asU8).orElse(-1);
	}

	public int getPartialRefreshGranularityY() {
		return get(TLV_PARTIAL_REFRESH_GRANULARITY_Y).map(Tlv::asU8).orElse(-1);
	}

	public int getActiveTransport() {
		return get(TLV_ACTIVE_TRANSPORT).map(Tlv::asU8).orElse(-1);
	}

	/** Raw GPIO numbers safe to use with §15 commands; empty if none reported. */
	public byte[] getAvailableGpioPins() {
		return get(TLV_AVAILABLE_GPIO_PINS).map(Tlv::getValue).orElse(new byte[0]);
	}

	public int getLastWakeReason() {
		return get(TLV_LAST_WAKE_REASON).map(Tlv::asU8).orElse(-1);
	}

	public String getDeviceName() {
		return get(TLV_DEVICE_NAME).map(Tlv::asUtf8).orElse(null);
	}

	public int getPixelPitchXUm() {
		return get(TLV_PIXEL_PITCH_X_UM).map(Tlv::asU16LE).orElse(-1);
	}

	public int getPixelPitchYUm() {
		return get(TLV_PIXEL_PITCH_Y_UM).map(Tlv::asU16LE).orElse(-1);
	}

	/**
	 * Derived from {@link #getPixelPitchXUm()} - doc/PROTOCOL.md design note 42:
	 * the wire reports raw pixel pitch, not a pre-rounded DPI, so this division is
	 * deliberately left to the client. {@link Double#NaN} if the device didn't
	 * report a pitch.
	 */
	public double getDpiX() {
		int pitch = getPixelPitchXUm();
		return pitch > 0 ? UM_PER_INCH / pitch : Double.NaN;
	}

	public double getDpiY() {
		int pitch = getPixelPitchYUm();
		return pitch > 0 ? UM_PER_INCH / pitch : Double.NaN;
	}

	/**
	 * What THIS handshake actually achieved (doc/PROTOCOL.md §5.3) - one of
	 * {@link AuthLevel}'s values.
	 */
	public int getGrantedLevel() {
		return get(TLV_GRANTED_LEVEL).map(Tlv::asU8).orElse(AuthLevel.NONE);
	}

	/**
	 * Whether a usage PIN is currently configured on the device - informational,
	 * not itself sensitive.
	 */
	public boolean isUsagePinRequired() {
		return get(TLV_USAGE_PIN_REQUIRED).map(Tlv::asU8).orElse(0) != 0;
	}

	/**
	 * Whether an admin PIN is currently configured on the device - informational,
	 * not itself sensitive.
	 */
	public boolean isAdminPinRequired() {
		return get(TLV_ADMIN_PIN_REQUIRED).map(Tlv::asU8).orElse(0) != 0;
	}
}
