package cz.bliksoft.hmieink.protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * A local-file "transport": instead of talking to a live device, every {@link #send} is recorded
 * as a {@link MacroCodec.Entry} and {@link #close()} writes the accumulated entries to a
 * {@code .macro} file (§18) via {@link MacroCodec#encode} - "generate a macro locally" without a
 * device attached. No `provided` dependency (unlike {@link SerialFrameTransport}/
 * {@link BleFrameTransport}) - built entirely on core classes already in this module.
 *
 * <p>
 * Every {@link #send} synchronously delivers a synthetic {@code ACK} (matching {@code
 * REF_SEQ}/{@code REF_COMMAND_ID}, {@code STATUS=OK}) to the registered {@link FrameListener}
 * before returning, so {@link CommandClient#send} unblocks immediately - this keeps {@link
 * HmiDevice}'s code path identical across all four transport kinds, with no special-casing
 * elsewhere. One real caveat: a command with an "inherent data response" (doc/PROTOCOL.md §10,
 * e.g. {@code HANDSHAKE_REQUEST}, {@code READ_SCREEN}) only ever gets this synthetic bare ACK
 * here, never real device data - the same limitation real firmware's own macro recording already
 * has for those commands (there's no live device to actually answer them).
 */
public final class FileFrameTransport implements FrameTransport {

	private final Path outputPath;
	private final List<MacroCodec.Entry> entries = new ArrayList<>();

	private volatile FrameListener listener;
	private volatile boolean connected;
	private int seqCounter;

	public FileFrameTransport(String path) {
		this.outputPath = Paths.get(path);
	}

	@Override
	public synchronized void connect() {
		connected = true;
	}

	@Override
	public synchronized void send(Frame frame) {
		if (!connected) {
			throw new IllegalStateException("not connected");
		}
		entries.add(new MacroCodec.Entry(frame.getCommandId(), frame.getPayload()));
		FrameListener l = listener;
		if (l != null) {
			byte[] ackPayload = { (byte) frame.getSeq(), (byte) (frame.getCommandId() & 0xFF),
					(byte) ((frame.getCommandId() >> 8) & 0xFF), (byte) Status.OK };
			l.onFrame(new Frame(CommandId.ACK, seqCounter++ & 0xFF, ackPayload));
		}
	}

	@Override
	public void setListener(FrameListener listener) {
		this.listener = listener;
	}

	@Override
	public boolean isConnected() {
		return connected;
	}

	/** Writes every recorded entry to the target file via {@link MacroCodec#encode}. Idempotent. */
	@Override
	public synchronized void close() throws IOException {
		if (!connected) {
			return;
		}
		connected = false;
		Files.write(outputPath, MacroCodec.encode(entries));
	}
}
