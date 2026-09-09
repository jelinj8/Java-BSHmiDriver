package cz.bliksoft.hmieink.protocol;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Custom PackBits-inspired RLE codec for 1bpp bitmap payloads (doc/PROTOCOL.md §6). Operates on
 * whole bytes of the raw bitmap. Not compatible with off-the-shelf TIFF PackBits decoders.
 *
 * <pre>
 * Control byte C:
 *   C in [0,127]:   LITERAL run  - next (C+1) bytes copied verbatim (1..128 bytes)
 *   C in [128,255]: REPEAT run   - runLen = (C-128)+2 (2..129); next ONE byte repeated runLen times
 * </pre>
 */
public final class RlePackBits {

	private static final int MAX_LITERAL_RUN = 128;
	private static final int MAX_REPEAT_RUN = 129;

	private RlePackBits() {
	}

	public static byte[] encode(byte[] data) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int i = 0;
		int n = data.length;
		while (i < n) {
			int runLen = runLengthAt(data, i);
			if (runLen >= 2) {
				out.write(128 + (runLen - 2));
				out.write(data[i]);
				i += runLen;
			} else {
				int litStart = i;
				int litLen = 0;
				while (i < n && litLen < MAX_LITERAL_RUN && runLengthAt(data, i) < 2) {
					i++;
					litLen++;
				}
				out.write(litLen - 1);
				out.write(data, litStart, litLen);
			}
		}
		return out.toByteArray();
	}

	private static int runLengthAt(byte[] data, int pos) {
		int n = data.length;
		int len = 1;
		while (pos + len < n && data[pos + len] == data[pos] && len < MAX_REPEAT_RUN) {
			len++;
		}
		return len;
	}

	public static byte[] decode(byte[] encoded, int decodedLen) {
		byte[] out = new byte[decodedLen];
		int oi = 0;
		int ei = 0;
		while (ei < encoded.length) {
			int c = encoded[ei++] & 0xFF;
			if (c <= 127) {
				int len = c + 1;
				if (ei + len > encoded.length || oi + len > decodedLen) {
					throw new IllegalArgumentException("RLE literal run overruns buffer");
				}
				System.arraycopy(encoded, ei, out, oi, len);
				ei += len;
				oi += len;
			} else {
				int runLen = (c - 128) + 2;
				if (ei >= encoded.length || oi + runLen > decodedLen) {
					throw new IllegalArgumentException("RLE repeat run overruns buffer");
				}
				byte value = encoded[ei++];
				Arrays.fill(out, oi, oi + runLen, value);
				oi += runLen;
			}
		}
		if (oi != decodedLen) {
			throw new IllegalArgumentException("RLE decoded length mismatch: expected " + decodedLen + " got " + oi);
		}
		return out;
	}
}
