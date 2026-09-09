package cz.bliksoft.hmieink.protocol;

/**
 * Access-control levels (doc/PROTOCOL.md §5.3) - reused directly as {@code HANDSHAKE_REQUEST}'s
 * own {@code PIN_TYPE} field values ("which tier does this PIN authenticate for" and "what tier
 * has this connection been granted" are the same concept). Mirrors firmware's {@code Protocol.h}
 * {@code authLevel} namespace - keep both in sync.
 */
public final class AuthLevel {

	private AuthLevel() {
	}

	public static final int NONE = 0x00;
	public static final int USAGE = 0x01;
	public static final int ADMIN = 0x02;
}
