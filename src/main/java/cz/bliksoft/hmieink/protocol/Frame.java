package cz.bliksoft.hmieink.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The common Logical Frame envelope shared by all three transports (TCP, BLE,
 * Serial). See doc/PROTOCOL.md §2.
 *
 * <pre>
 * Offset  Size  Field
 * 0       1     MAGIC        0xA5
 * 1       1     VERSION
 * 2       2     COMMAND_ID   u16 LE
 * 4       1     SEQ
 * 5       4     PAYLOAD_LEN  u32 LE
 * 9       N     PAYLOAD
 * 9+N     2     CRC16        u16 LE, over VERSION..end of PAYLOAD
 * </pre>
 */
public final class Frame {

	public static final int MAGIC = 0xA5;
	public static final int VERSION = 0x01;
	public static final int HEADER_SIZE = 9;
	public static final int CRC_SIZE = 2;

	private final int version;
	private final int commandId;
	private final int seq;
	private final byte[] payload;

	public Frame(int commandId, int seq, byte[] payload) {
		this(VERSION, commandId, seq, payload);
	}

	public Frame(int version, int commandId, int seq, byte[] payload) {
		if (version < 0 || version > 0xFF) {
			throw new IllegalArgumentException("version out of range: " + version);
		}
		if (commandId < 0 || commandId > 0xFFFF) {
			throw new IllegalArgumentException("commandId out of range: " + commandId);
		}
		if (seq < 0 || seq > 0xFF) {
			throw new IllegalArgumentException("seq out of range: " + seq);
		}
		this.version = version;
		this.commandId = commandId;
		this.seq = seq;
		this.payload = payload != null ? payload : new byte[0];
	}

	public int getVersion() {
		return version;
	}

	public int getCommandId() {
		return commandId;
	}

	public int getSeq() {
		return seq;
	}

	public byte[] getPayload() {
		return payload;
	}

	/**
	 * Serializes this frame to wire bytes, computing and appending the CRC16
	 * trailer.
	 */
	public byte[] encode() {
		int total = HEADER_SIZE + payload.length + CRC_SIZE;
		ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
		buf.put((byte) MAGIC);
		buf.put((byte) version);
		buf.putShort((short) commandId);
		buf.put((byte) seq);
		buf.putInt(payload.length);
		buf.put(payload);
		int crc = Crc16.compute(buf.array(), 1, HEADER_SIZE - 1 + payload.length);
		buf.putShort((short) crc);
		return buf.array();
	}

	/**
	 * Parses exactly one frame from {@code bytes} (no trailing data allowed - the
	 * caller is responsible for delimiting frames on streamed transports, or
	 * reassembling BLE chunks, before calling this).
	 *
	 * @throws FrameException if the buffer is too short, MAGIC is wrong, the
	 *                        declared PAYLOAD_LEN doesn't match the buffer length,
	 *                        or the CRC16 doesn't match.
	 */
	public static Frame decode(byte[] bytes) {
		if (bytes.length < HEADER_SIZE + CRC_SIZE) {
			throw new FrameException("Frame too short: " + bytes.length + " bytes");
		}
		ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		int magic = buf.get() & 0xFF;
		if (magic != MAGIC) {
			throw new FrameException(String.format("Bad MAGIC: expected 0x%02X got 0x%02X", MAGIC, magic));
		}
		int version = buf.get() & 0xFF;
		int commandId = buf.getShort() & 0xFFFF;
		int seq = buf.get() & 0xFF;
		long payloadLenL = buf.getInt() & 0xFFFFFFFFL;
		if (payloadLenL > bytes.length) {
			throw new FrameException("PAYLOAD_LEN " + payloadLenL + " exceeds buffer size " + bytes.length);
		}
		int payloadLen = (int) payloadLenL;
		int expectedTotal = HEADER_SIZE + payloadLen + CRC_SIZE;
		if (bytes.length != expectedTotal) {
			throw new FrameException("Frame length mismatch: expected " + expectedTotal + " got " + bytes.length);
		}
		byte[] payload = new byte[payloadLen];
		buf.get(payload);
		int crcReceived = buf.getShort() & 0xFFFF;
		int crcComputed = Crc16.compute(bytes, 1, HEADER_SIZE - 1 + payloadLen);
		if (crcReceived != crcComputed) {
			throw new FrameException(
					String.format("CRC16 mismatch: expected 0x%04X got 0x%04X", crcComputed, crcReceived));
		}
		return new Frame(version, commandId, seq, payload);
	}
}
