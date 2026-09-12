package cz.bliksoft.hmieink.protocol.schema;

/**
 * One field of a {@link CommandSpec}, in wire order. Field values passed
 * to/from {@link PayloadCodec} are: {@link Long} for
 * {@code U8}/{@code U16LE}/{@code S16LE}/ {@code U32LE}, {@link String} for
 * {@code STRING}, {@code byte[]} for {@code BYTES}, and
 * {@code java.util.List<String>} for {@code REPEATED_STRING_TAIL}.
 *
 * <p>
 * A {@code STRING}/{@code BYTES} field carries its own length prefix
 * ({@link #lengthPrefix}) or, for {@code NONE}, reads its length from an
 * earlier field named {@link #lengthField}.
 * {@code REPEATED_STRING_TAIL}/{@code REPEATED_U16LE_TAIL} carry no count of
 * their own on the wire either - every use in this protocol already has an
 * explicit, separately-declared count field just before them (e.g.
 * {@code DRAW_IMAGE_ROW.COUNT}, {@code GPIO_PLAY_PATTERN.STEP_COUNT} - both
 * {@code derived}, computed as the list's own size on encode), referenced the
 * same way via {@link #lengthField}.
 *
 * <p>
 * {@link #derived} fields are never supplied by a caller (skipped by
 * {@code TextCommandFormat}'s parser and by {@code HmiDevice}'s typed methods)
 * - {@link PayloadCodec#encode} computes their value via {@link #deriveFn}.
 * This covers every length/count prefix that isn't baked directly into a
 * {@code STRING}/{@code BYTES}/{@code REPEATED_STRING_TAIL} field's own
 * encoding (e.g. image/screen commands' {@code DECODED_LEN}/{@code ENCODED_LEN}
 * pair, or {@code OTA_INSTALL}'s {@code HASH_LEN}). Derived fields are still
 * fully decoded and shown when reading an arbitrary payload back (e.g.
 * {@code HmiDevice#describe}) - "derived" only means "not required as input".
 *
 * <p>
 * A {@code U8} field's symbolic names come from {@link #enumClasses} - more
 * than one when a byte combines two namespaces (e.g.
 * {@code SET_WIFI_CONFIG.FLAGS} = {@code ConfigFlags} +
 * {@code WifiConfigFlags}). {@link #isBitmask} distinguishes a single-value
 * field (one symbolic name or a raw int) from a bitmask field (one or more
 * names joined by {@code +}, since the default {@code |} field separator is
 * already taken); the reflection-based name lookup also skips any constant
 * named {@code FLAG_*} for single-value resolution, so a field like
 * {@code PowerMode.MODE} (which shares its class with the unrelated bitmask
 * constant {@code PowerMode.FLAG_KEEP_BLE_CONNECTABLE}) can't accidentally
 * resolve to it.
 */
public final class FieldSpec {

	public final String name;
	public final FieldKind kind;
	public final LengthPrefix lengthPrefix;
	public final String lengthField;
	public final Class<?>[] enumClasses;
	public final boolean isBitmask;
	public final boolean derived;
	public final DerivedValueFn deriveFn;

	private FieldSpec(String name, FieldKind kind, LengthPrefix lengthPrefix, String lengthField,
			Class<?>[] enumClasses, boolean isBitmask, boolean derived, DerivedValueFn deriveFn) {
		this.name = name;
		this.kind = kind;
		this.lengthPrefix = lengthPrefix;
		this.lengthField = lengthField;
		this.enumClasses = enumClasses;
		this.isBitmask = isBitmask;
		this.derived = derived;
		this.deriveFn = deriveFn;
	}

	public static FieldSpec u8(String name) {
		return new FieldSpec(name, FieldKind.U8, null, null, null, false, false, null);
	}

	public static FieldSpec u8Enum(String name, Class<?>... enumClasses) {
		return new FieldSpec(name, FieldKind.U8, null, null, enumClasses, false, false, null);
	}

	public static FieldSpec u8Flags(String name, Class<?>... enumClasses) {
		return new FieldSpec(name, FieldKind.U8, null, null, enumClasses, true, false, null);
	}

	public static FieldSpec u16le(String name) {
		return new FieldSpec(name, FieldKind.U16LE, null, null, null, false, false, null);
	}

	public static FieldSpec s16le(String name) {
		return new FieldSpec(name, FieldKind.S16LE, null, null, null, false, false, null);
	}

	public static FieldSpec u32le(String name) {
		return new FieldSpec(name, FieldKind.U32LE, null, null, null, false, false, null);
	}

	public static FieldSpec string(String name, LengthPrefix lengthPrefix) {
		return new FieldSpec(name, FieldKind.STRING, lengthPrefix, null, null, false, false, null);
	}

	public static FieldSpec bytes(String name, LengthPrefix lengthPrefix) {
		return new FieldSpec(name, FieldKind.BYTES, lengthPrefix, null, null, false, false, null);
	}

	/**
	 * A {@code BYTES} field whose length was already established by an earlier
	 * {@code derived} field.
	 */
	public static FieldSpec bytesRef(String name, String lengthField) {
		return new FieldSpec(name, FieldKind.BYTES, LengthPrefix.NONE, lengthField, null, false, false, null);
	}

	/**
	 * {@code countField} names the earlier {@code derived} field carrying this
	 * list's entry count.
	 */
	public static FieldSpec repeatedStringTail(String name, String countField) {
		return new FieldSpec(name, FieldKind.REPEATED_STRING_TAIL, null, countField, null, false, false, null);
	}

	public static FieldSpec repeatedU16leTail(String name, String countField) {
		return new FieldSpec(name, FieldKind.REPEATED_U16LE_TAIL, null, countField, null, false, false, null);
	}

	public static FieldSpec ipv4(String name) {
		return new FieldSpec(name, FieldKind.IPV4, null, null, null, false, false, null);
	}

	public static FieldSpec mac6(String name) {
		return new FieldSpec(name, FieldKind.MAC6, null, null, null, false, false, null);
	}

	/**
	 * Returns a copy of this field marked {@link #derived}, computed via {@code fn}
	 * on encode.
	 */
	public FieldSpec derived(DerivedValueFn fn) {
		return new FieldSpec(name, kind, lengthPrefix, lengthField, enumClasses, isBitmask, true, fn);
	}
}
