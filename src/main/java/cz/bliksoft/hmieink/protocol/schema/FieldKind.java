package cz.bliksoft.hmieink.protocol.schema;

/**
 * The wire shape of one {@link FieldSpec}. {@code IPV4}/{@code MAC6} are
 * fixed-size raw byte fields (4 and 6 bytes respectively) with a natural
 * human-readable text form ("A.B.C.D" / "AA:BB:CC:DD:EE:FF").
 * {@code REPEATED_STRING_TAIL}/{@code REPEATED_U16LE_TAIL} both consume every
 * remaining token on a text command line (one list entry per token) - only ever
 * the last field in a {@link CommandSpec}.
 */
public enum FieldKind {
	U8, U16LE, U32LE, S16LE, IPV4, MAC6, STRING, BYTES, REPEATED_STRING_TAIL, REPEATED_U16LE_TAIL
}
