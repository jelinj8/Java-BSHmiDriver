package cz.bliksoft.hmieink.text;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cz.bliksoft.hmieink.image.IconSpecCache;
import cz.bliksoft.hmieink.protocol.schema.CommandSchema;
import cz.bliksoft.hmieink.protocol.schema.CommandSpec;
import cz.bliksoft.hmieink.protocol.schema.FieldKind;
import cz.bliksoft.hmieink.protocol.schema.FieldSpec;
import cz.bliksoft.hmieink.protocol.schema.PayloadCodec;

/**
 * The plaintext one-line command notation:
 * {@code NAME<sep>field1<sep>field2<sep>...}, driven entirely by
 * {@link CommandSchema} so it never drifts from the typed wire layout. Default
 * separator is {@code |}; the CLI's {@code -s} lets a caller pick a different
 * one when a field's own text needs to contain a literal {@code |}.
 *
 * <p>
 * String-valued tokens support backslash escapes: {@code \n}, {@code \t},
 * {@code \\}, {@code
 * \<separator>}, and {@code \\uXXXX} (a 4-hex-digit Unicode code unit) - this
 * is what lets a single shell-line {@code -c} argument carry a
 * newline/tab/arbitrary Unicode character. A {@code BYTES} field's token is
 * either escaped text (UTF-8 encoded), {@code @<local file path>} to read raw
 * bytes from a file, or {@code #<name>} to reuse the {@code .epi} bytes an
 * {@code ICONSPEC} pseudo-command (see {@code ScriptRunner}) cached under that
 * name in {@link IconSpecCache} (used for image/OTA/upload payloads -
 * RLE-compressed transfers are out of scope here, see {@link PayloadCodec}'s
 * class doc). Enum-valued fields accept the symbolic constant name
 * (case-insensitive) or a raw integer (decimal, or {@code 0x}-prefixed hex); a
 * bitmask field ({@link FieldSpec#isBitmask}) accepts one or more names joined
 * by {@code +} (not {@code |}, so it keeps working under any {@code -s}
 * separator).
 *
 * <p>
 * {@link #format} always renders every field, including
 * {@link FieldSpec#derived} ones (e.g. a length/count prefix) - {@link #parse}
 * never requires them as input, but seeing them in output keeps a decoded
 * frame's text fully self-describing (the point of "translate frames back to
 * text representation", e.g. for reading back a downloaded {@code .macro}
 * recording).
 */
public final class TextCommandFormat {

	/**
	 * The result of {@link #parse}: a command ready to hand to
	 * {@code CommandClient#send}.
	 */
	public static final class ParsedCommand {
		public final int commandId;
		public final byte[] payload;

		public ParsedCommand(int commandId, byte[] payload) {
			this.commandId = commandId;
			this.payload = payload;
		}
	}

	private TextCommandFormat() {
	}

	public static ParsedCommand parse(String line, char separator) {
		List<String> tokens = tokenize(line, separator);
		if (tokens.isEmpty() || tokens.get(0).isEmpty()) {
			throw new IllegalArgumentException("empty command line");
		}
		CommandSpec spec = CommandSchema.byName(tokens.get(0));
		if (!spec.sendable) {
			throw new IllegalArgumentException(spec.name + " is device→PC only, it cannot be sent");
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		int tokenIndex = 1;
		for (FieldSpec f : spec.fields) {
			if (f.derived) {
				continue;
			}
			if (f.kind == FieldKind.REPEATED_STRING_TAIL) {
				List<String> rest = new ArrayList<>(tokens.subList(tokenIndex, tokens.size()));
				fields.put(f.name, rest);
				tokenIndex = tokens.size();
				continue;
			}
			if (f.kind == FieldKind.REPEATED_U16LE_TAIL) {
				List<Long> rest = new ArrayList<>();
				for (String t : tokens.subList(tokenIndex, tokens.size())) {
					rest.add(parseIntToken(t, f));
				}
				fields.put(f.name, rest);
				tokenIndex = tokens.size();
				continue;
			}
			if (tokenIndex >= tokens.size()) {
				throw new IllegalArgumentException(spec.name + ": missing value for field '" + f.name + "'");
			}
			String token = tokens.get(tokenIndex++);
			fields.put(f.name, parseFieldValue(token, f));
		}
		if (tokenIndex != tokens.size()) {
			throw new IllegalArgumentException(spec.name + ": too many fields (expected " + (tokenIndex - 1) + ", got "
					+ (tokens.size() - 1) + ")");
		}
		byte[] payload = PayloadCodec.encode(spec, fields);
		return new ParsedCommand(spec.commandId, payload);
	}

	public static String format(int commandId, byte[] payload, char separator) {
		CommandSpec spec = CommandSchema.byId(commandId);
		Map<String, Object> fields = PayloadCodec.decode(spec, payload);
		StringBuilder sb = new StringBuilder(spec.name);
		for (FieldSpec f : spec.fields) {
			Object value = fields.get(f.name);
			appendField(sb, separator, f, value);
		}
		return sb.toString();
	}

	// --- field value parsing (text token -> typed value) ---

	private static Object parseFieldValue(String token, FieldSpec f) {
		switch (f.kind) {
		case U8:
		case U16LE:
		case S16LE:
		case U32LE:
			return parseIntToken(token, f);
		case IPV4:
			return parseIpv4(token);
		case MAC6:
			return parseMac6(token);
		case STRING:
			return token; // already fully unescaped by tokenize()
		case BYTES:
			return parseBytesToken(token);
		default:
			throw new IllegalArgumentException("field '" + f.name + "' cannot be parsed from a single token");
		}
	}

	/**
	 * Resolves one {@code BYTES}-field token: {@code @<file>} reads raw bytes from
	 * a file, {@code #<name>} resolves an {@link IconSpecCache} entry, anything
	 * else is taken as literal UTF-8 text. Exposed publicly (beyond
	 * {@link #parse}'s own use) so other line-oriented consumers of this same
	 * grammar (e.g. {@code ScriptRunner}'s {@code OTA} pseudo-command) can reuse
	 * the exact same {@code @}/{@code #} resolution rather than reimplementing it -
	 * {@link #tokenize} already gives {@code @}/{@code #}-prefixed tokens the same
	 * raw (no backslash-escape processing) treatment for exactly this reason.
	 */
	public static byte[] parseBytesToken(String token) {
		if (token.startsWith("@")) {
			try {
				return Files.readAllBytes(Paths.get(token.substring(1)));
			} catch (IOException e) {
				throw new IllegalArgumentException("cannot read file '" + token.substring(1) + "': " + e.getMessage(),
						e);
			}
		}
		if (token.startsWith("#")) {
			String name = token.substring(1);
			byte[] cached = IconSpecCache.get(name);
			if (cached == null) {
				throw new IllegalArgumentException(
						"no image cached as '" + name + "' - run ICONSPEC|" + name + "|<spec> first");
			}
			return cached;
		}
		return token.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] parseIpv4(String token) {
		String[] parts = token.split("\\.");
		if (parts.length != 4) {
			throw new IllegalArgumentException("not a valid IPv4 address: " + token);
		}
		byte[] b = new byte[4];
		for (int i = 0; i < 4; i++) {
			b[i] = (byte) Integer.parseInt(parts[i].trim());
		}
		return b;
	}

	private static byte[] parseMac6(String token) {
		String[] parts = token.split(":");
		if (parts.length != 6) {
			throw new IllegalArgumentException("not a valid MAC address: " + token);
		}
		byte[] b = new byte[6];
		for (int i = 0; i < 6; i++) {
			b[i] = (byte) Integer.parseInt(parts[i].trim(), 16);
		}
		return b;
	}

	private static long parseIntToken(String token, FieldSpec f) {
		String t = token.trim();
		if (f.enumClasses != null && f.enumClasses.length > 0) {
			if (f.isBitmask) {
				long value = 0;
				for (String part : t.split("\\+")) {
					value |= resolvePart(part.trim(), f.enumClasses);
				}
				return value;
			}
			Long resolved = tryResolveName(f.enumClasses, t, false);
			if (resolved != null) {
				return resolved;
			}
		}
		if (t.equalsIgnoreCase("true")) {
			return 1L;
		}
		if (t.equalsIgnoreCase("false")) {
			return 0L;
		}
		return parsePlainNumber(t);
	}

	private static long resolvePart(String part, Class<?>[] classes) {
		Long byName = tryResolveName(classes, part, true);
		if (byName != null) {
			return byName;
		}
		return parsePlainNumber(part);
	}

	private static long parsePlainNumber(String t) {
		boolean neg = t.startsWith("-");
		String body = neg ? t.substring(1) : t;
		long v = (body.startsWith("0x") || body.startsWith("0X")) ? Long.parseLong(body.substring(2), 16)
				: Long.parseLong(body);
		return neg ? -v : v;
	}

	private static Long tryResolveName(Class<?>[] classes, String name, boolean includeFlagPrefixed) {
		for (Class<?> c : classes) {
			for (Field field : c.getFields()) {
				if (!isConstant(field) || (!includeFlagPrefixed && field.getName().startsWith("FLAG_"))) {
					continue;
				}
				if (field.getName().equalsIgnoreCase(name)) {
					try {
						return (long) field.getInt(null);
					} catch (IllegalAccessException e) {
						throw new IllegalStateException(e);
					}
				}
			}
		}
		return null;
	}

	private static boolean isConstant(Field f) {
		int mods = f.getModifiers();
		return Modifier.isStatic(mods) && Modifier.isFinal(mods) && f.getType() == int.class;
	}

	// --- field value formatting (typed value -> text token) ---

	@SuppressWarnings("unchecked")
	private static void appendField(StringBuilder sb, char separator, FieldSpec f, Object value) {
		sb.append(separator);
		switch (f.kind) {
		case U8:
		case U16LE:
		case S16LE:
		case U32LE:
			sb.append(formatIntValue((Long) value, f));
			return;
		case IPV4: {
			byte[] b = (byte[]) value;
			sb.append(b[0] & 0xFF).append('.').append(b[1] & 0xFF).append('.').append(b[2] & 0xFF).append('.')
					.append(b[3] & 0xFF);
			return;
		}
		case MAC6: {
			byte[] b = (byte[]) value;
			for (int i = 0; i < 6; i++) {
				if (i > 0) {
					sb.append(':');
				}
				sb.append(String.format("%02X", b[i] & 0xFF));
			}
			return;
		}
		case STRING:
			sb.append(escape((String) value, separator));
			return;
		case BYTES:
			sb.append(formatBytesValue((byte[]) value, separator));
			return;
		case REPEATED_STRING_TAIL:
			for (String s : (List<String>) value) {
				sb.append(escape(s, separator)).append(separator);
			}
			sb.setLength(sb.length() - 1); // drop the trailing separator this loop over-appended
			return;
		case REPEATED_U16LE_TAIL:
			for (Long v : (List<Long>) value) {
				sb.append(v).append(separator);
			}
			sb.setLength(sb.length() - 1);
			return;
		default:
			throw new IllegalStateException("unhandled field kind " + f.kind);
		}
	}

	private static String formatBytesValue(byte[] b, char separator) {
		String text = tryDecodeUtf8(b);
		return text != null ? escape(text, separator) : "0x" + toHex(b);
	}

	private static String tryDecodeUtf8(byte[] b) {
		try {
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b)).toString();
		} catch (CharacterCodingException e) {
			return null;
		}
	}

	private static String toHex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte value : b) {
			sb.append(String.format("%02X", value & 0xFF));
		}
		return sb.toString();
	}

	private static String formatIntValue(long value, FieldSpec f) {
		if (f.enumClasses == null || f.enumClasses.length == 0) {
			return Long.toString(value);
		}
		if (f.isBitmask) {
			return formatBitmask(value, f.enumClasses);
		}
		String name = reverseResolveName(f.enumClasses, value, false);
		return name != null ? name : Long.toString(value);
	}

	private static String formatBitmask(long value, Class<?>[] classes) {
		List<String> matched = new ArrayList<>();
		long remaining = value;
		for (Class<?> c : classes) {
			for (Field field : c.getFields()) {
				if (!isConstant(field)) {
					continue;
				}
				try {
					long bit = field.getInt(null);
					if (bit != 0 && (remaining & bit) == bit) {
						matched.add(field.getName());
						remaining &= ~bit;
					}
				} catch (IllegalAccessException e) {
					throw new IllegalStateException(e);
				}
			}
		}
		if (matched.isEmpty() && remaining == 0) {
			return "0";
		}
		StringBuilder sb = new StringBuilder();
		for (String name : matched) {
			if (sb.length() > 0) {
				sb.append('+');
			}
			sb.append(name);
		}
		if (remaining != 0) {
			if (sb.length() > 0) {
				sb.append('+');
			}
			sb.append("0x").append(Long.toHexString(remaining));
		}
		return sb.toString();
	}

	private static String reverseResolveName(Class<?>[] classes, long value, boolean includeFlagPrefixed) {
		for (Class<?> c : classes) {
			for (Field field : c.getFields()) {
				if (!isConstant(field) || (!includeFlagPrefixed && field.getName().startsWith("FLAG_"))) {
					continue;
				}
				try {
					if (field.getInt(null) == value) {
						return field.getName();
					}
				} catch (IllegalAccessException e) {
					throw new IllegalStateException(e);
				}
			}
		}
		return null;
	}

	// --- escaping / tokenizing ---

	/**
	 * Splits one command line into separator-delimited, fully-unescaped tokens -
	 * exposed publicly (beyond {@link #parse}'s own use) so other line-oriented
	 * consumers of this same grammar (e.g. {@code ScriptRunner}'s PC-local
	 * pseudo-commands) can reuse the exact same escaping rules rather than
	 * re-implementing a slightly different one.
	 */
	public static List<String> tokenize(String line, char separator) {
		List<String> tokens = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		int i = 0;
		// A token starting with '@' (the "read a local file" convention) or '#' (the
		// "read from IconSpecCache" convention, see BYTES fields) is taken verbatim,
		// with no escape processing at all - only the next literal separator ends it.
		// Otherwise an ordinary Windows path (backslashes throughout) would collide
		// with this same escape syntax (e.g. "\Users" parsed as an unrecognized \U
		// escape).
		boolean rawMode = !line.isEmpty() && (line.charAt(0) == '@' || line.charAt(0) == '#');
		while (i < line.length()) {
			char c = line.charAt(i);
			if (!rawMode && c == '\\' && i + 1 < line.length()) {
				char next = line.charAt(i + 1);
				if (next == 'n') {
					cur.append('\n');
					i += 2;
				} else if (next == 't') {
					cur.append('\t');
					i += 2;
				} else if (next == '\\') {
					cur.append('\\');
					i += 2;
				} else if (next == separator) {
					cur.append(separator);
					i += 2;
				} else if (next == 'u') {
					if (i + 6 > line.length()) {
						throw new IllegalArgumentException("truncated \\u escape at position " + i);
					}
					cur.append((char) Integer.parseInt(line.substring(i + 2, i + 6), 16));
					i += 6;
				} else {
					throw new IllegalArgumentException("unrecognized escape '\\" + next + "' at position " + i);
				}
			} else if (c == separator) {
				tokens.add(cur.toString());
				cur.setLength(0);
				i++;
				rawMode = i < line.length() && (line.charAt(i) == '@' || line.charAt(i) == '#');
			} else {
				cur.append(c);
				i++;
			}
		}
		tokens.add(cur.toString());
		return tokens;
	}

	/**
	 * Inverse of {@link #tokenize} for one field's text - see that method's doc for
	 * why this is public.
	 */
	public static String escape(String s, char separator) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\') {
				sb.append("\\\\");
			} else if (c == '\n') {
				sb.append("\\n");
			} else if (c == '\t') {
				sb.append("\\t");
			} else if (c == separator) {
				sb.append('\\').append(separator);
			} else if (c < 0x20 || c == 0x7F) {
				sb.append(String.format("\\u%04X", (int) c));
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
}
