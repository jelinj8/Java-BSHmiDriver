package cz.bliksoft.hmieink.protocol.schema;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cz.bliksoft.hmieink.protocol.OtaHashAlgo;

/**
 * Every registered {@link CommandSpec} - the full catalog, both sendable and decode-only - must
 * round-trip byte-identically through {@code encode -> decode -> encode}. Cheap, broad coverage
 * of the whole command set rather than a handful of hand-picked examples; catches a
 * transcription mistake in {@link CommandSchema} (a wrong field order/kind/length-prefix) that a
 * narrower test could easily miss.
 */
class CommandSchemaRoundTripTest {

	@Test
	void everyCommandRoundTrips() {
		for (CommandSpec spec : CommandSchema.all()) {
			try {
				byte[] encoded1 = PayloadCodec.encode(spec, sampleFields(spec));
				Map<String, Object> decoded = PayloadCodec.decode(spec, encoded1);
				byte[] encoded2 = PayloadCodec.encode(spec, decoded);
				assertArrayEquals(encoded1, encoded2, spec.name + " did not round-trip byte-identically");
			} catch (RuntimeException e) {
				throw new AssertionError(spec.name + ": " + e, e);
			}
		}
	}

	private static Map<String, Object> sampleFields(CommandSpec spec) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (FieldSpec f : spec.fields) {
			if (f.derived) {
				continue;
			}
			m.put(f.name, sampleValue(spec, f));
		}
		return m;
	}

	private static Object sampleValue(CommandSpec spec, FieldSpec f) {
		// OTA_INSTALL.HASH_LEN is derived from HASH_ALGO (OtaHashAlgo.hashLenFor), so HASH's own
		// sample length must actually match that algorithm's real digest size.
		if ("OTA_INSTALL".equals(spec.name) && "HASH_ALGO".equals(f.name)) {
			return (long) OtaHashAlgo.SHA256;
		}
		if ("OTA_INSTALL".equals(spec.name) && "HASH".equals(f.name)) {
			return new byte[32];
		}
		// SCREEN_DATA is decode-only (a real device fills DECODED_LEN/ENCODED_LEN independently,
		// not derived from DATA here) - its sample DATA length must still agree with them for a
		// self-consistent round-trip fixture.
		if ("SCREEN_DATA".equals(spec.name) && ("DECODED_LEN".equals(f.name) || "ENCODED_LEN".equals(f.name))) {
			return (long) "some-bytes".getBytes(StandardCharsets.UTF_8).length;
		}
		switch (f.kind) {
			case U8:
				return 2L;
			case U16LE:
				return 300L;
			case S16LE:
				return -123L;
			case U32LE:
				return 70000L;
			case IPV4:
				return new byte[] { 1, 2, 3, 4 };
			case MAC6:
				return new byte[] { 1, 2, 3, 4, 5, 6 };
			case STRING:
				return "hello";
			case BYTES:
				return "some-bytes".getBytes(StandardCharsets.UTF_8);
			case REPEATED_STRING_TAIL:
				return Arrays.asList("a.epi", "b.epi", "c.epi");
			case REPEATED_U16LE_TAIL:
				return Arrays.asList(100L, 200L, 300L);
			default:
				throw new IllegalStateException("no sample value for field kind " + f.kind);
		}
	}
}
