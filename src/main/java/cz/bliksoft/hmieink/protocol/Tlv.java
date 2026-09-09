package cz.bliksoft.hmieink.protocol;

import java.nio.charset.StandardCharsets;

/**
 * One TLV entry (doc/PROTOCOL.md §5.1): a TYPE byte, a LENGTH byte, and up to 255 bytes of VALUE.
 * Used by the handshake response (§5.2, see {@link HandshakeCapabilities}) and CONFIG_BACKUP/
 * RESTORE (§13).
 */
public final class Tlv {

	private final int type;
	private final byte[] value;

	public Tlv(int type, byte[] value) {
		if (type < 0 || type > 0xFF) {
			throw new IllegalArgumentException("type out of range: " + type);
		}
		if (value.length > 0xFF) {
			throw new IllegalArgumentException("value too long for a single TLV entry: " + value.length + " bytes");
		}
		this.type = type;
		this.value = value;
	}

	public int getType() {
		return type;
	}

	public byte[] getValue() {
		return value;
	}

	public int asU8() {
		requireLength(1);
		return value[0] & 0xFF;
	}

	public int asU16LE() {
		requireLength(2);
		return (value[0] & 0xFF) | ((value[1] & 0xFF) << 8);
	}

	public long asU32LE() {
		requireLength(4);
		return (value[0] & 0xFFL) | ((value[1] & 0xFFL) << 8) | ((value[2] & 0xFFL) << 16) | ((value[3] & 0xFFL) << 24);
	}

	public String asUtf8() {
		return new String(value, StandardCharsets.UTF_8);
	}

	private void requireLength(int expected) {
		if (value.length != expected) {
			throw new IllegalStateException(
					"TLV type 0x" + Integer.toHexString(type) + " has length " + value.length + ", expected " + expected);
		}
	}
}
