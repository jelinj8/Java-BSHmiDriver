package cz.bliksoft.hmieink.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class FrameStreamReaderTest {

	@Test
	void readsOneFrame() throws IOException {
		byte[] wire = new Frame(CommandId.HANDSHAKE_REQUEST, 0, null).encode();
		FrameStreamReader reader = new FrameStreamReader(new ByteArrayInputStream(wire));

		Frame frame = reader.readFrame();
		assertEquals(CommandId.HANDSHAKE_REQUEST, frame.getCommandId());
	}

	@Test
	void readsTwoConcatenatedFramesInOrder() throws IOException {
		byte[] first = new Frame(CommandId.HANDSHAKE_REQUEST, 0, null).encode();
		byte[] second = new Frame(CommandId.BUTTON_EVENT, 1, "hi".getBytes(StandardCharsets.UTF_8)).encode();
		byte[] wire = concat(first, second);
		FrameStreamReader reader = new FrameStreamReader(new ByteArrayInputStream(wire));

		Frame f1 = reader.readFrame();
		Frame f2 = reader.readFrame();
		assertEquals(CommandId.HANDSHAKE_REQUEST, f1.getCommandId());
		assertEquals(CommandId.BUTTON_EVENT, f2.getCommandId());
		assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), f2.getPayload());
	}

	@Test
	void handlesFragmentedDelivery() throws IOException {
		byte[] wire = new Frame(CommandId.DRAW_LINE, 5, new byte[] { 1, 2, 3, 4, 5 }).encode();
		// Deliver one byte at a time to exercise the "not enough bytes yet" path
		// repeatedly.
		FrameStreamReader reader = new FrameStreamReader(new OneByteAtATimeInputStream(wire));

		Frame frame = reader.readFrame();
		assertEquals(CommandId.DRAW_LINE, frame.getCommandId());
		assertEquals(5, frame.getSeq());
	}

	@Test
	void skipsLeadingGarbageBeforeMagic() throws IOException {
		byte[] garbage = { 0x00, 0x11, 0x22, (byte) 0xFF };
		byte[] wire = new Frame(CommandId.ACK, 0, null).encode();
		FrameStreamReader reader = new FrameStreamReader(new ByteArrayInputStream(concat(garbage, wire)));

		Frame frame = reader.readFrame();
		assertEquals(CommandId.ACK, frame.getCommandId());
	}

	@Test
	void resyncsPastACorruptedFrameToTheNextValidOne() throws IOException {
		byte[] corrupted = new Frame(CommandId.NACK, 3, new byte[] { 9, 9, 9 }).encode();
		corrupted[corrupted.length - 1] ^= 0xFF; // flip a CRC byte
		byte[] good = new Frame(CommandId.HANDSHAKE_RESPONSE, 4, null).encode();
		FrameStreamReader reader = new FrameStreamReader(new ByteArrayInputStream(concat(corrupted, good)));

		Frame frame = reader.readFrame();
		assertEquals(CommandId.HANDSHAKE_RESPONSE, frame.getCommandId());
		assertEquals(4, frame.getSeq());
	}

	@Test
	void throwsEofWhenStreamClosesMidFrame() {
		byte[] wire = new Frame(CommandId.DRAW_RECT, 0, new byte[] { 1, 2, 3 }).encode();
		byte[] truncated = java.util.Arrays.copyOf(wire, wire.length - 2);
		FrameStreamReader reader = new FrameStreamReader(new ByteArrayInputStream(truncated));

		assertThrows(EOFException.class, reader::readFrame);
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] out = new byte[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	/**
	 * Forces FrameStreamReader through its "need more bytes" path on every single
	 * byte.
	 */
	private static final class OneByteAtATimeInputStream extends InputStream {
		private final byte[] data;
		private int pos = 0;

		OneByteAtATimeInputStream(byte[] data) {
			this.data = data;
		}

		@Override
		public int read() {
			return pos < data.length ? (data[pos++] & 0xFF) : -1;
		}

		@Override
		public int read(byte[] b, int off, int len) {
			if (pos >= data.length) {
				return -1;
			}
			b[off] = data[pos++];
			return 1;
		}
	}
}
