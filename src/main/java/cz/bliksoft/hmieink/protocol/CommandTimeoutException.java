package cz.bliksoft.hmieink.protocol;

import java.io.IOException;

/**
 * Thrown by {@link CommandClient#send} when no correlated response arrives within the timeout,
 * even after the one retry doc/PROTOCOL.md §10 specifies ("a retry resends the same SEQ").
 */
public final class CommandTimeoutException extends IOException {

	private static final long serialVersionUID = 1L;

	private final int commandId;
	private final int seq;

	CommandTimeoutException(int commandId, int seq) {
		super(String.format("no response to commandId=0x%04X seq=%d within timeout (after 1 retry)", commandId, seq));
		this.commandId = commandId;
		this.seq = seq;
	}

	public int getCommandId() {
		return commandId;
	}

	public int getSeq() {
		return seq;
	}
}
