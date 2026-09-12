package cz.bliksoft.hmieink.protocol;

import java.io.IOException;

/**
 * Thrown by {@link CommandClient#send} when the device replies NACK
 * (doc/PROTOCOL.md §10).
 */
public final class CommandNackException extends IOException {

	private static final long serialVersionUID = 1L;

	private final int refSeq;
	private final int refCommandId;
	private final int status;

	private CommandNackException(int refSeq, int refCommandId, int status) {
		super(String.format("NACK status=0x%02X for commandId=0x%04X seq=%d", status, refCommandId, refSeq));
		this.refSeq = refSeq;
		this.refCommandId = refCommandId;
		this.status = status;
	}

	/**
	 * Parses a raw NACK frame's payload (doc/PROTOCOL.md §10: REF_SEQ,
	 * REF_COMMAND_ID u16 LE, STATUS).
	 */
	static CommandNackException fromNackFrame(Frame nack) {
		byte[] p = nack.getPayload();
		int refSeq = p.length > 0 ? p[0] & 0xFF : -1;
		int refCommandId = p.length > 2 ? ((p[1] & 0xFF) | ((p[2] & 0xFF) << 8)) : -1;
		int status = p.length > 3 ? p[3] & 0xFF : Status.UNKNOWN_ERROR;
		return new CommandNackException(refSeq, refCommandId, status);
	}

	/** One of the {@link Status} constants. */
	public int getStatus() {
		return status;
	}

	public int getRefCommandId() {
		return refCommandId;
	}

	public int getRefSeq() {
		return refSeq;
	}
}
