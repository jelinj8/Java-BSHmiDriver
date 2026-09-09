package cz.bliksoft.hmieink.protocol.schema;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One command's shape: its {@code COMMAND_ID}, symbolic name (matches {@code CommandId}'s own
 * constant name), field layout in wire order, and whether a caller can send it. {@code
 * sendable=false} marks a device→PC-only command (a response or an unsolicited event) - included
 * in {@link CommandSchema} purely so {@code HmiDevice#describe} can render it, never something a
 * caller constructs and sends.
 */
public final class CommandSpec {

	public final int commandId;
	public final String name;
	public final List<FieldSpec> fields;
	public final boolean sendable;

	public CommandSpec(int commandId, String name, boolean sendable, FieldSpec... fields) {
		this.commandId = commandId;
		this.name = name;
		this.sendable = sendable;
		this.fields = Collections.unmodifiableList(Arrays.asList(fields));
	}
}
