package cz.bliksoft.hmieink.protocol;

/**
 * ACK/NACK status codes (doc/PROTOCOL.md §10). Mirrors firmware's
 * {@code Protocol.h} {@code status} namespace - keep both in sync.
 */
public final class Status {

	private Status() {
	}

	public static final int OK = 0x00;
	public static final int CRC_FAIL = 0x01;
	public static final int BUSY = 0x02;
	public static final int UNSUPPORTED_COMMAND = 0x03;
	public static final int BAD_PARAMETERS = 0x04;
	public static final int DECODE_FAIL = 0x05;
	public static final int VERSION_MISMATCH = 0x06;
	public static final int CHUNK_SEQUENCE_ERROR = 0x07;
	public static final int FILE_NOT_FOUND = 0x08;
	public static final int INSUFFICIENT_STORAGE = 0x09;
	public static final int VOLUME_NOT_PRESENT = 0x0A;
	public static final int PIN_UNAVAILABLE = 0x0B;
	public static final int OTA_HASH_MISMATCH = 0x0C;
	public static final int OTA_NOT_STAGED = 0x0D;
	/**
	 * Granted access level insufficient for the command's effective required tier
	 * (doc/PROTOCOL.md §5.3).
	 */
	public static final int NOT_AUTHORIZED = 0x0E;
	public static final int UNKNOWN_ERROR = 0xFF;
}
