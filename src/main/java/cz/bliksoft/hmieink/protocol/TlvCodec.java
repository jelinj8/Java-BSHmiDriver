package cz.bliksoft.hmieink.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Encodes/decodes back-to-back §5.1 TLV entries, as used by the handshake payload (§5.2, see
 * {@link HandshakeCapabilities}) and CONFIG_BACKUP/RESTORE (§13). Mirrors the inline TLV helpers
 * in firmware's main.cpp.
 */
public final class TlvCodec {

	private TlvCodec() {
	}

	/**
	 * Decodes a flat back-to-back TLV byte stream. A truncated trailing entry (declared LENGTH
	 * would run past the end of {@code payload}) is silently dropped rather than throwing - mirrors
	 * §5.1's "unknown TYPE -> skip" tolerance philosophy for malformed/short data too.
	 */
	public static List<Tlv> decode(byte[] payload) {
		List<Tlv> entries = new ArrayList<>();
		int i = 0;
		while (i + 2 <= payload.length) {
			int type = payload[i] & 0xFF;
			int len = payload[i + 1] & 0xFF;
			int valueStart = i + 2;
			if (valueStart + len > payload.length) {
				break;
			}
			entries.add(new Tlv(type, Arrays.copyOfRange(payload, valueStart, valueStart + len)));
			i = valueStart + len;
		}
		return entries;
	}

	/** Builds a flat back-to-back TLV byte stream, e.g. for CONFIG_RESTORE (§13). */
	public static final class Builder {

		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		public Builder u8(int type, int value) {
			return raw(type, new byte[] { (byte) value });
		}

		public Builder u16LE(int type, int value) {
			return raw(type, new byte[] { (byte) value, (byte) (value >> 8) });
		}

		public Builder u32LE(int type, long value) {
			return raw(type,
					new byte[] { (byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24) });
		}

		public Builder utf8(int type, String value) {
			return raw(type, value.getBytes(StandardCharsets.UTF_8));
		}

		public Builder raw(int type, byte[] value) {
			if (type < 0 || type > 0xFF) {
				throw new IllegalArgumentException("type out of range: " + type);
			}
			if (value.length > 0xFF) {
				throw new IllegalArgumentException("value too long for a single TLV entry: " + value.length + " bytes");
			}
			out.write(type);
			out.write(value.length);
			out.write(value, 0, value.length);
			return this;
		}

		public byte[] build() {
			return out.toByteArray();
		}
	}
}
