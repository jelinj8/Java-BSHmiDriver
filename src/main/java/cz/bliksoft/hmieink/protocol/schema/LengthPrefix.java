package cz.bliksoft.hmieink.protocol.schema;

/**
 * How a {@link FieldKind#STRING}/{@link FieldKind#BYTES} field's byte length is carried on the
 * wire. {@code NONE} means the length instead comes from an earlier field, named by
 * {@link FieldSpec#lengthField} (e.g. OTA_INSTALL's {@code IMAGE_DATA} is exactly {@code
 * TOTAL_LEN} bytes, with other fields in between).
 */
public enum LengthPrefix {
	U8, U16LE, U32LE, NONE
}
