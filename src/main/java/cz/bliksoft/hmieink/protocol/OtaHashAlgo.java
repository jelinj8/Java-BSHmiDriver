package cz.bliksoft.hmieink.protocol;

/**
 * {@code OTA_INSTALL.HASH_ALGO} (doc/PROTOCOL.md §16.1). Mirrors firmware's
 * {@code Protocol.h} {@code otaHashAlgo} namespace.
 */
public final class OtaHashAlgo {

	private OtaHashAlgo() {
	}

	public static final int NONE = 0x00;
	public static final int SHA256 = 0x01;
	public static final int MD5 = 0x02;

	/** HASH_LEN in bytes for a given HASH_ALGO (0 for NONE). */
	public static int hashLenFor(int hashAlgo) {
		switch (hashAlgo) {
		case SHA256:
			return 32;
		case MD5:
			return 16;
		default:
			return 0;
		}
	}
}
