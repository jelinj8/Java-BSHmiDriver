package cz.bliksoft.hmieink.protocol.schema;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes/decodes a {@link CommandSpec}'s payload bytes to/from an ordered field-name-to-value
 * map. Integer-kind fields ({@code U8}/{@code U16LE}/{@code S16LE}/{@code U32LE}) use
 * {@link Long}; {@code STRING} uses {@link String}; {@code BYTES}/{@code IPV4}/{@code MAC6} use
 * {@code byte[]}; {@code REPEATED_STRING_TAIL} uses {@code List<String>}; {@code
 * REPEATED_U16LE_TAIL} uses {@code List<Long>}.
 *
 * <p>
 * {@link #encode} takes only the non-{@link FieldSpec#derived} fields a caller supplied -
 * derived fields (every length/count prefix not already baked into a {@code STRING}/{@code
 * BYTES}/repeated field's own encoding) are computed automatically, in wire order, via each
 * field's {@link FieldSpec#deriveFn}. This is deliberately agnostic to {@link FieldSpec#enumClasses}/
 * {@link FieldSpec#isBitmask} - symbolic name resolution is a text-format concern (see the
 * {@code text} package), not a wire-encoding one.
 */
public final class PayloadCodec {

	private PayloadCodec() {
	}

	public static byte[] encode(CommandSpec spec, Map<String, Object> suppliedFields) {
		Map<String, Object> combined = new LinkedHashMap<>(suppliedFields);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (FieldSpec f : spec.fields) {
			Object value;
			if (f.derived) {
				value = f.deriveFn.compute(combined);
				combined.put(f.name, value);
			} else {
				value = combined.get(f.name);
			}
			if (value == null) {
				throw new IllegalArgumentException("missing field '" + f.name + "' for " + spec.name);
			}
			writeField(out, combined, f, value);
		}
		return out.toByteArray();
	}

	public static LinkedHashMap<String, Object> decode(CommandSpec spec, byte[] payload) {
		LinkedHashMap<String, Object> result = new LinkedHashMap<>();
		ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
		for (FieldSpec f : spec.fields) {
			result.put(f.name, readField(buf, result, f));
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static void writeField(ByteArrayOutputStream out, Map<String, Object> combined, FieldSpec f,
			Object value) {
		switch (f.kind) {
			case U8:
				out.write((int) ((long) (Long) value & 0xFF));
				break;
			case U16LE: {
				long v = (Long) value;
				out.write((int) (v & 0xFF));
				out.write((int) ((v >> 8) & 0xFF));
				break;
			}
			case S16LE: {
				// same 2's-complement bit pattern as U16LE for a value already in [-32768,32767].
				long v = (Long) value;
				out.write((int) (v & 0xFF));
				out.write((int) ((v >> 8) & 0xFF));
				break;
			}
			case U32LE: {
				long v = (Long) value;
				out.write((int) (v & 0xFF));
				out.write((int) ((v >> 8) & 0xFF));
				out.write((int) ((v >> 16) & 0xFF));
				out.write((int) ((v >> 24) & 0xFF));
				break;
			}
			case IPV4:
			case MAC6: {
				byte[] b = (byte[]) value;
				out.write(b, 0, b.length);
				break;
			}
			case STRING: {
				byte[] b = ((String) value).getBytes(StandardCharsets.UTF_8);
				writeLengthPrefix(out, f, b.length);
				out.write(b, 0, b.length);
				break;
			}
			case BYTES: {
				byte[] b = (byte[]) value;
				writeLengthPrefix(out, f, b.length);
				out.write(b, 0, b.length);
				break;
			}
			case REPEATED_STRING_TAIL: {
				List<String> list = (List<String>) value;
				for (String s : list) {
					byte[] b = s.getBytes(StandardCharsets.UTF_8);
					out.write(b.length & 0xFF);
					out.write(b, 0, b.length);
				}
				break;
			}
			case REPEATED_U16LE_TAIL: {
				List<Long> list = (List<Long>) value;
				for (Long v : list) {
					out.write((int) (v & 0xFF));
					out.write((int) ((v >> 8) & 0xFF));
				}
				break;
			}
			default:
				throw new IllegalStateException("unhandled field kind " + f.kind);
		}
	}

	private static void writeLengthPrefix(ByteArrayOutputStream out, FieldSpec f, int length) {
		switch (f.lengthPrefix) {
			case U8:
				out.write(length & 0xFF);
				break;
			case U16LE:
				out.write(length & 0xFF);
				out.write((length >> 8) & 0xFF);
				break;
			case U32LE:
				out.write(length & 0xFF);
				out.write((length >> 8) & 0xFF);
				out.write((length >> 16) & 0xFF);
				out.write((length >> 24) & 0xFF);
				break;
			case NONE:
				// either "rest of payload" (no field references this one's length) or a value
				// already emitted earlier as its own derived length field (bytesRef) - either way,
				// nothing more to write here.
				break;
			default:
				throw new IllegalStateException("unhandled length prefix " + f.lengthPrefix);
		}
	}

	private static Object readField(ByteBuffer buf, Map<String, Object> soFar, FieldSpec f) {
		switch (f.kind) {
			case U8:
				return (long) (buf.get() & 0xFF);
			case U16LE:
				return (long) (buf.getShort() & 0xFFFF);
			case S16LE:
				return (long) buf.getShort();
			case U32LE:
				return buf.getInt() & 0xFFFFFFFFL;
			case IPV4: {
				byte[] b = new byte[4];
				buf.get(b);
				return b;
			}
			case MAC6: {
				byte[] b = new byte[6];
				buf.get(b);
				return b;
			}
			case STRING: {
				int len = readLength(buf, soFar, f);
				byte[] b = new byte[len];
				buf.get(b);
				return new String(b, StandardCharsets.UTF_8);
			}
			case BYTES: {
				int len = readLength(buf, soFar, f);
				byte[] b = new byte[len];
				buf.get(b);
				return b;
			}
			case REPEATED_STRING_TAIL: {
				int count = ((Number) soFar.get(f.lengthField)).intValue();
				List<String> list = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					int len = buf.get() & 0xFF;
					byte[] b = new byte[len];
					buf.get(b);
					list.add(new String(b, StandardCharsets.UTF_8));
				}
				return list;
			}
			case REPEATED_U16LE_TAIL: {
				int count = ((Number) soFar.get(f.lengthField)).intValue();
				List<Long> list = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					list.add((long) (buf.getShort() & 0xFFFF));
				}
				return list;
			}
			default:
				throw new IllegalStateException("unhandled field kind " + f.kind);
		}
	}

	private static int readLength(ByteBuffer buf, Map<String, Object> soFar, FieldSpec f) {
		switch (f.lengthPrefix) {
			case U8:
				return buf.get() & 0xFF;
			case U16LE:
				return buf.getShort() & 0xFFFF;
			case U32LE:
				return buf.getInt(); // realistic payload sizes in this project never approach 2^31
			case NONE:
				if (f.lengthField != null) {
					return ((Number) soFar.get(f.lengthField)).intValue();
				}
				return buf.remaining();
			default:
				throw new IllegalStateException("unhandled length prefix " + f.lengthPrefix);
		}
	}
}
