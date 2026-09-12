package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;

/**
 * Manual, real-hardware verification of FAST_CLEAR (doc/PROTOCOL.md §12.16) -
 * the one §12 primitive that deliberately bypasses
 * SET_CLIP_REGION/SET_DRAW_OFFSET/SET_ORIENTATION, requested directly: "fast
 * buffer filling with 1 or 0... skipping all clipping and mapping guards".
 * Confirms a full-panel BLACK and WHITE fill each read back correctly via
 * READ_SCREEN at three sampled points, and - the interesting case - that an
 * active SET_CLIP_REGION set to a small sub-rectangle beforehand does NOT
 * constrain FAST_CLEAR at all: a point well outside that clip still gets
 * filled. Response payloads are RLE-decoded properly (READ_SCREEN's ENCODING
 * byte is not always RAW) - an earlier ad hoc version of this check skipped
 * that and produced a false "BLACK doesn't work" result, see design note 84.
 * NOT part of the automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.FastClearManualCheck COM5
 * </pre>
 */
public final class FastClearManualCheck {

	private static int failures = 0;

	private FastClearManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: FastClearManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + "...");
		client.connect();
		try {
			System.out.println("-> validation: bad payload size should NACK(BAD_PARAMETERS)");
			expectNack(client, new byte[] { 0x00 });
			System.out.println("-> validation: invalid COLOR should NACK(BAD_PARAMETERS)");
			expectNack(client, new byte[] { 0x02, 0x03 });

			System.out.println("-> FAST_CLEAR BLACK, REFRESH_NOW|REFRESH_FULL");
			long t0 = System.currentTimeMillis();
			client.send(CommandId.FAST_CLEAR, new byte[] { 0x01, 0x03 });
			System.out.println("   took " + (System.currentTimeMillis() - t0) + "ms");
			checkPanel(client, "BLACK", true);

			System.out.println("-> FAST_CLEAR WHITE, REFRESH_NOW|REFRESH_FULL");
			t0 = System.currentTimeMillis();
			client.send(CommandId.FAST_CLEAR, new byte[] { 0x00, 0x03 });
			System.out.println("   took " + (System.currentTimeMillis() - t0) + "ms");
			checkPanel(client, "WHITE", false);

			System.out.println("-> SET_CLIP_REGION (100,100,50,50), then FAST_CLEAR BLACK - should still "
					+ "fill the whole panel, ignoring the clip");
			setClipRegion(client, 100, 100, 50, 50);
			client.send(CommandId.FAST_CLEAR, new byte[] { 0x01, 0x03 });
			checkPixel(client, 5, 5, true, "corner (5,5), well outside the clip region");
			setClipRegion(client, 0, 0, 400, 300); // restore full-panel clip
			client.send(CommandId.FAST_CLEAR, new byte[] { 0x00, 0x03 }); // leave the panel blank white

			System.out.println();
			if (failures == 0) {
				System.out.println("ALL CHECKS PASSED");
			} else {
				System.out.println(failures + " CHECK(S) FAILED - see above");
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	private static void setClipRegion(CommandClient client, int x, int y, int w, int h) throws Exception {
		ByteBuffer payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) w);
		payload.putShort((short) h);
		client.send(CommandId.SET_CLIP_REGION, payload.array());
	}

	private static void checkPanel(CommandClient client, String label, boolean expectBlack) throws Exception {
		checkPixel(client, 0, 0, expectBlack, label + " top-left (0,0)");
		checkPixel(client, 399, 299, expectBlack, label + " bottom-right (399,299)");
		checkPixel(client, 200, 150, expectBlack, label + " center (200,150)");
	}

	private static void checkPixel(CommandClient client, int x, int y, boolean expectBlack, String label)
			throws Exception {
		ByteBuffer req = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
		req.put((byte) 0x00); // SOURCE=PANEL
		req.put((byte) 0x01); // MODE=REGION
		req.putShort((short) x);
		req.putShort((short) y);
		req.putShort((short) 1);
		req.putShort((short) 1);
		Frame r = client.send(CommandId.READ_SCREEN, req.array());
		byte[] p = r.getPayload();
		int encoding = p[0] & 0xFF;
		int decodedLen = (int) readU32LE(p, 9);
		int encodedLen = (int) readU32LE(p, 13);
		byte[] decoded = encoding == 0x00 ? Arrays.copyOfRange(p, 17, 17 + decodedLen)
				: rleDecode(p, 17, encodedLen, decodedLen);
		boolean isBlack = (decoded[0] & 0x80) != 0;
		check(label, isBlack == expectBlack);
	}

	// PackBits-style decode matching doc/PROTOCOL.md §6's RLE_PACKBITS scheme.
	private static byte[] rleDecode(byte[] src, int offset, int encodedLen, int decodedLen) {
		byte[] out = new byte[decodedLen];
		int outPos = 0;
		int i = offset;
		int end = offset + encodedLen;
		while (i < end && outPos < decodedLen) {
			int c = src[i++] & 0xFF;
			if (c <= 127) {
				int n = c + 1;
				System.arraycopy(src, i, out, outPos, n);
				i += n;
				outPos += n;
			} else {
				int n = (c - 128) + 2;
				byte v = src[i++];
				for (int k = 0; k < n && outPos < decodedLen; k++) {
					out[outPos++] = v;
				}
			}
		}
		return out;
	}

	private static long readU32LE(byte[] data, int offset) {
		return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8) | ((data[offset + 2] & 0xFFL) << 16)
				| ((data[offset + 3] & 0xFFL) << 24);
	}

	private static void expectNack(CommandClient client, byte[] payload) throws Exception {
		try {
			client.send(CommandId.FAST_CLEAR, payload);
			check("expected NACK but got ACK", false);
		} catch (CommandNackException e) {
			check("got NACK(0x" + Integer.toHexString(e.getStatus()) + ")", true);
		}
	}

	private static void check(String description, boolean ok) {
		if (ok) {
			System.out.println("   OK: " + description);
		} else {
			System.out.println("   FAIL: " + description);
			failures++;
		}
	}
}
