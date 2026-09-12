package cz.bliksoft.hmieink.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import cz.bliksoft.hmieink.protocol.schema.CommandSchema;
import cz.bliksoft.hmieink.protocol.schema.PayloadCodec;

/**
 * In the same package as {@link CommandClientTest} specifically to reuse its
 * {@link FakeFrameTransport} test double (package-private), same as
 * {@link ScriptRunnerTest}.
 */
class HmiDeviceImageEncodingTest {

	@Test
	void fullImageTransferPicksRleForHighlyCompressibleData() throws IOException {
		FakeFrameTransport transport = ackingTransport();
		HmiDevice device = new HmiDevice(transport);

		byte[] rawBitmap = new byte[15000]; // all-zero: maximally compressible

		device.fullImageTransfer(rawBitmap, WriteFlags.REFRESH_NOW);

		Map<String, Object> fields = decode(CommandId.FULL_IMAGE_TRANSFER, transport);
		assertEquals((long) Encoding.RLE_PACKBITS, fields.get("ENCODING"));
		assertEquals((long) rawBitmap.length, fields.get("DECODED_LEN"));
		assertTrue((Long) fields.get("ENCODED_LEN") < rawBitmap.length,
				"RLE-encoded all-zero bitmap should be much smaller than raw");
	}

	@Test
	void fullImageTransferPicksRawForIncompressibleData() throws IOException {
		FakeFrameTransport transport = ackingTransport();
		HmiDevice device = new HmiDevice(transport);

		byte[] rawBitmap = new byte[15000];
		new Random(42).nextBytes(rawBitmap); // random: RLE can't beat this

		device.fullImageTransfer(rawBitmap, WriteFlags.REFRESH_NOW);

		Map<String, Object> fields = decode(CommandId.FULL_IMAGE_TRANSFER, transport);
		assertEquals((long) Encoding.RAW, fields.get("ENCODING"));
		assertEquals((long) rawBitmap.length, fields.get("DECODED_LEN"));
		assertEquals((long) rawBitmap.length, fields.get("ENCODED_LEN"));
	}

	@Test
	void partialImageTransferPicksRleForHighlyCompressibleData() throws IOException {
		FakeFrameTransport transport = ackingTransport();
		HmiDevice device = new HmiDevice(transport);

		byte[] rawBitmap = new byte[500];

		device.partialImageTransfer(0, 0, 100, 40, rawBitmap, WriteFlags.REFRESH_NOW);

		Map<String, Object> fields = decode(CommandId.PARTIAL_IMAGE_TRANSFER, transport);
		assertEquals((long) Encoding.RLE_PACKBITS, fields.get("ENCODING"));
		assertEquals((long) rawBitmap.length, fields.get("DECODED_LEN"));
		assertTrue((Long) fields.get("ENCODED_LEN") < rawBitmap.length);
	}

	@Test
	void partialImageTransferPicksRawForIncompressibleData() throws IOException {
		FakeFrameTransport transport = ackingTransport();
		HmiDevice device = new HmiDevice(transport);

		byte[] rawBitmap = new byte[500];
		new Random(7).nextBytes(rawBitmap);

		device.partialImageTransfer(0, 0, 100, 40, rawBitmap, WriteFlags.REFRESH_NOW);

		Map<String, Object> fields = decode(CommandId.PARTIAL_IMAGE_TRANSFER, transport);
		assertEquals((long) Encoding.RAW, fields.get("ENCODING"));
		assertEquals((long) rawBitmap.length, fields.get("DECODED_LEN"));
		assertEquals((long) rawBitmap.length, fields.get("ENCODED_LEN"));
	}

	private static FakeFrameTransport ackingTransport() {
		FakeFrameTransport transport = new FakeFrameTransport();
		transport.setResponder(request -> new Frame(CommandId.ACK, request.getSeq(),
				new byte[] { (byte) request.getSeq(), 0, 0, Status.OK }));
		return transport;
	}

	private static Map<String, Object> decode(int commandId, FakeFrameTransport transport) {
		assertEquals(1, transport.getSent().size());
		Frame sent = transport.getSent().get(0);
		assertEquals(commandId, sent.getCommandId());
		return PayloadCodec.decode(CommandSchema.byId(commandId), sent.getPayload());
	}
}
