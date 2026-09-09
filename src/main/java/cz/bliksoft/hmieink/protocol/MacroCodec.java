package cz.bliksoft.hmieink.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Encoder/decoder for the {@code .macro} file format (doc/PROTOCOL.md §0x0A00) - a simple sequence
 * of (COMMAND_ID, PAYLOAD) entries, the exact same shape firmware's RECORD_MACRO/SAVE_MACRO produce
 * and PLAY_MACRO consumes. Lets a PC application hand-author a macro (e.g. a boot-time demo) without
 * needing a live RECORD_MACRO session first.
 */
public final class MacroCodec {

	private static final byte[] MAGIC = { 'M', 'A', 'C', '1' };
	private static final int FORMAT_VERSION = 0x01;
	private static final int HEADER_SIZE = 5;

	/** One captured/authored command: its COMMAND_ID and raw wire payload. */
	public static final class Entry {
		public final int commandId;
		public final byte[] payload;

		public Entry(int commandId, byte[] payload) {
			this.commandId = commandId;
			this.payload = payload;
		}
	}

	private MacroCodec() {
	}

	public static byte[] encode(List<Entry> entries) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(MAGIC, 0, MAGIC.length);
		out.write(FORMAT_VERSION);
		for (Entry entry : entries) {
			out.write(entry.commandId & 0xFF);
			out.write((entry.commandId >> 8) & 0xFF);
			int len = entry.payload.length;
			out.write(len & 0xFF);
			out.write((len >> 8) & 0xFF);
			out.write((len >> 16) & 0xFF);
			out.write((len >> 24) & 0xFF);
			out.write(entry.payload, 0, entry.payload.length);
		}
		return out.toByteArray();
	}

	public static List<Entry> decode(byte[] macro) {
		if (macro.length < HEADER_SIZE || macro[0] != MAGIC[0] || macro[1] != MAGIC[1] || macro[2] != MAGIC[2]
				|| macro[3] != MAGIC[3] || (macro[4] & 0xFF) != FORMAT_VERSION) {
			throw new IllegalArgumentException("not a valid .macro file (bad magic/version)");
		}
		List<Entry> entries = new ArrayList<>();
		int pos = HEADER_SIZE;
		while (pos < macro.length) {
			if (pos + 6 > macro.length) {
				throw new IllegalArgumentException("truncated .macro entry header");
			}
			int commandId = (macro[pos] & 0xFF) | ((macro[pos + 1] & 0xFF) << 8);
			long payloadLen = (macro[pos + 2] & 0xFFL) | ((macro[pos + 3] & 0xFFL) << 8)
					| ((macro[pos + 4] & 0xFFL) << 16) | ((macro[pos + 5] & 0xFFL) << 24);
			pos += 6;
			if (pos + payloadLen > macro.length) {
				throw new IllegalArgumentException("truncated .macro entry payload");
			}
			byte[] payload = new byte[(int) payloadLen];
			System.arraycopy(macro, pos, payload, 0, (int) payloadLen);
			entries.add(new Entry(commandId, payload));
			pos += (int) payloadLen;
		}
		return entries;
	}
}
