package cz.bliksoft.hmieink.script;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cz.bliksoft.hmieink.image.IconSpecCache;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.FakeFrameTransport;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.HandshakeCapabilities;
import cz.bliksoft.hmieink.protocol.HmiDevice;
import cz.bliksoft.hmieink.protocol.OtaHashAlgo;
import cz.bliksoft.hmieink.protocol.OtaInstallFlags;
import cz.bliksoft.hmieink.protocol.Status;
import cz.bliksoft.hmieink.protocol.TlvCodec;
import cz.bliksoft.hmieink.protocol.schema.CommandSchema;
import cz.bliksoft.hmieink.protocol.schema.PayloadCodec;

/**
 * Reuses {@link FakeFrameTransport} (public, originally package-private
 * alongside {@code CommandClientTest} in {@code cz.bliksoft.hmieink.protocol})
 * rather than inventing a second in-memory transport test double.
 */
class ScriptRunnerTest {

	private static final PrintStream QUIET = new PrintStream(new OutputStream() {
		@Override
		public void write(int b) {
		}
	});

	@TempDir
	Path tempDir;

	@Test
	void sleepBlocksLocallyAndSendsNothingToTheDevice() throws IOException {
		FakeFrameTransport transport = new FakeFrameTransport();
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		long start = System.currentTimeMillis();
		runner.runLine("SLEEP|150", '|');
		long elapsed = System.currentTimeMillis() - start;

		assertTrue(elapsed >= 150, "SLEEP should block for at least the requested duration, took " + elapsed + "ms");
		assertTrue(transport.getSent().isEmpty(), "SLEEP must never send anything to the device");
	}

	@Test
	void sleepIsCaseInsensitive() throws IOException {
		FakeFrameTransport transport = new FakeFrameTransport();
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		runner.runLine("sleep|10", '|');

		assertTrue(transport.getSent().isEmpty());
	}

	@Test
	void waitLogWithoutMarkerMatchesAnyLogMessage() throws Exception {
		FakeFrameTransport transport = new FakeFrameTransport();
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		Thread pusher = new Thread(() -> {
			sleepUnchecked(50);
			transport.pushUnsolicited(new Frame(CommandId.LOG_MESSAGE, 0, "anything".getBytes(StandardCharsets.UTF_8)));
		});
		pusher.start();

		runner.runLine("WAIT_LOG|2000", '|'); // must not throw
		pusher.join();
	}

	@Test
	void waitLogWithMarkerIgnoresNonMatchingMessages() throws Exception {
		FakeFrameTransport transport = new FakeFrameTransport();
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		Thread pusher = new Thread(() -> {
			sleepUnchecked(30);
			transport.pushUnsolicited(new Frame(CommandId.LOG_MESSAGE, 0, "wrong".getBytes(StandardCharsets.UTF_8)));
			sleepUnchecked(30);
			transport
					.pushUnsolicited(new Frame(CommandId.LOG_MESSAGE, 1, "boot_done".getBytes(StandardCharsets.UTF_8)));
		});
		pusher.start();

		runner.runLine("WAIT_LOG|2000|boot_done", '|'); // must not throw
		pusher.join();
	}

	@Test
	void waitLogTimesOutIfNothingArrives() {
		FakeFrameTransport transport = new FakeFrameTransport();
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		assertThrows(IOException.class, () -> runner.runLine("WAIT_LOG|100", '|'));
	}

	@Test
	void ordinaryCommandsStillGoToTheDevice() throws IOException {
		FakeFrameTransport transport = new FakeFrameTransport();
		transport.setResponder(request -> new Frame(CommandId.ACK, request.getSeq(),
				new byte[] { (byte) request.getSeq(), 0, 0, Status.OK }));
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		runner.runLine("FAST_CLEAR|WHITE|0", '|');

		assertEquals(1, transport.getSent().size());
		assertEquals(CommandId.FAST_CLEAR, transport.getSent().get(0).getCommandId());
	}

	@AfterEach
	void clearIconSpecCache() {
		IconSpecCache.clear();
	}

	@Test
	void hashTokenResolvesBytesCachedByIconSpec() throws IOException {
		byte[] cached = { 5, 4, 3, 2, 1 };
		IconSpecCache.put("logo", cached);
		FakeFrameTransport transport = new FakeFrameTransport();
		transport.setResponder(request -> new Frame(CommandId.ACK, request.getSeq(),
				new byte[] { (byte) request.getSeq(), 0, 0, Status.OK }));
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		runner.runLine("LOG_MESSAGE|#logo", '|');

		Map<String, Object> fields = PayloadCodec.decode(CommandSchema.byId(CommandId.LOG_MESSAGE),
				transport.getSent().get(0).getPayload());
		assertArrayEquals(cached, (byte[]) fields.get("MARKER"));
	}

	@Test
	void hashTokenForUnknownNameThrows() {
		FakeFrameTransport transport = new FakeFrameTransport();
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		assertThrows(IllegalArgumentException.class, () -> runner.runLine("LOG_MESSAGE|#not_cached", '|'));
	}

	/**
	 * Responder simulating a device whose {@code RUNNING_SLOT} actually flips after
	 * {@code OTA_INSTALL} - lets {@code OTA}'s success-detection logic (compare
	 * {@code RUNNING_SLOT} before/after) run for real against
	 * {@link FakeFrameTransport}, and gives realistic {@code HANDSHAKE_RESPONSE}
	 * TLVs so {@link ScriptRunner#drawOtaResultMessage} has real fields to read.
	 */
	private static Function<Frame, Frame> otaSuccessResponder(boolean[] installed) {
		return request -> {
			switch (request.getCommandId()) {
			case CommandId.HANDSHAKE_REQUEST:
				byte[] handshakePayload = new TlvCodec.Builder().u16LE(HandshakeCapabilities.TLV_DISPLAY_WIDTH_PX, 400)
						.utf8(HandshakeCapabilities.TLV_DEVICE_NAME, "CrowPanel-Test")
						.utf8(HandshakeCapabilities.TLV_FIRMWARE_VERSION, "9.9.9-test").build();
				return new Frame(CommandId.HANDSHAKE_RESPONSE, request.getSeq(), handshakePayload);
			case CommandId.OTA_STATUS_REQUEST:
				byte[] statusPayload = PayloadCodec.encode(CommandSchema.byId(CommandId.OTA_STATUS_RESPONSE),
						fields("RUNNING_SLOT", installed[0] ? 1L : 0L, "PENDING_VERIFICATION", installed[0] ? 1L : 0L,
								"RUNNING_VERSION", "9.9.9-test"));
				return new Frame(CommandId.OTA_STATUS_RESPONSE, request.getSeq(), statusPayload);
			case CommandId.OTA_INSTALL:
				installed[0] = true;
				return new Frame(CommandId.ACK, request.getSeq(),
						new byte[] { (byte) request.getSeq(), 0, 0, Status.OK });
			default:
				return new Frame(CommandId.ACK, request.getSeq(),
						new byte[] { (byte) request.getSeq(), 0, 0, Status.OK });
			}
		};
	}

	private static Map<String, Object> fields(Object... kv) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			map.put((String) kv[i], kv[i + 1]);
		}
		return map;
	}

	@Test
	void otaReadsFileHashesItAndInstallsWithApplyNow() throws Exception {
		byte[] firmware = { 1, 2, 3, 4, 5, 6, 7, 8 };
		Path firmwareFile = tempDir.resolve("firmware.bin");
		Files.write(firmwareFile, firmware);

		FakeFrameTransport transport = new FakeFrameTransport();
		boolean[] installed = { false };
		transport.setResponder(otaSuccessResponder(installed));
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		runner.runLine("OTA|@" + firmwareFile, '|');

		List<Frame> sent = transport.getSent();
		assertEquals(6, sent.size(), "OTA_STATUS_REQUEST(before), OTA_INSTALL, HANDSHAKE_REQUEST, "
				+ "OTA_STATUS_REQUEST(after), OTA_CONFIRM, DRAW_TEXT(status banner)");
		assertEquals(CommandId.OTA_STATUS_REQUEST, sent.get(0).getCommandId());
		assertEquals(CommandId.OTA_INSTALL, sent.get(1).getCommandId());
		assertEquals(CommandId.HANDSHAKE_REQUEST, sent.get(2).getCommandId());
		assertEquals(CommandId.OTA_STATUS_REQUEST, sent.get(3).getCommandId());
		assertEquals(CommandId.OTA_CONFIRM, sent.get(4).getCommandId(), "success (slot changed) must send OTA_CONFIRM");
		assertEquals(CommandId.DRAW_TEXT, sent.get(5).getCommandId());

		Map<String, Object> fields = PayloadCodec.decode(CommandSchema.byId(CommandId.OTA_INSTALL),
				sent.get(1).getPayload());
		assertArrayEquals(firmware, (byte[]) fields.get("IMAGE_DATA"));
		assertEquals((long) OtaHashAlgo.SHA256, fields.get("HASH_ALGO"));
		assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(firmware), (byte[]) fields.get("HASH"));
		assertEquals((long) OtaInstallFlags.APPLY_NOW, fields.get("FLAGS"));
	}

	@Test
	void otaSkipsConfirmWhenTheDeviceIsStillOnThePreviousSlot() throws Exception {
		byte[] firmware = { 9, 9, 9 };
		Path firmwareFile = tempDir.resolve("firmware.bin");
		Files.write(firmwareFile, firmware);

		FakeFrameTransport transport = new FakeFrameTransport();
		// RUNNING_SLOT never changes - simulates an early/crash-triggered rollback
		// beating the reconnect to it.
		transport.setResponder(request -> {
			switch (request.getCommandId()) {
			case CommandId.HANDSHAKE_REQUEST:
				byte[] handshakePayload = new TlvCodec.Builder()
						.utf8(HandshakeCapabilities.TLV_DEVICE_NAME, "CrowPanel-Test")
						.utf8(HandshakeCapabilities.TLV_FIRMWARE_VERSION, "old-version").build();
				return new Frame(CommandId.HANDSHAKE_RESPONSE, request.getSeq(), handshakePayload);
			case CommandId.OTA_STATUS_REQUEST:
				byte[] statusPayload = PayloadCodec.encode(CommandSchema.byId(CommandId.OTA_STATUS_RESPONSE),
						fields("RUNNING_SLOT", 0L, "PENDING_VERIFICATION", 0L, "RUNNING_VERSION", "old-version"));
				return new Frame(CommandId.OTA_STATUS_RESPONSE, request.getSeq(), statusPayload);
			default:
				return new Frame(CommandId.ACK, request.getSeq(),
						new byte[] { (byte) request.getSeq(), 0, 0, Status.OK });
			}
		});
		HmiDevice device = new HmiDevice(transport);
		ScriptRunner runner = new ScriptRunner(device, QUIET);

		runner.runLine("OTA|@" + firmwareFile, '|');

		List<Frame> sent = transport.getSent();
		assertTrue(sent.stream().noneMatch(f -> f.getCommandId() == CommandId.OTA_CONFIRM),
				"must not confirm when RUNNING_SLOT never changed");
		assertTrue(sent.stream().anyMatch(f -> f.getCommandId() == CommandId.DRAW_TEXT),
				"must still draw a status banner even when the update didn't take effect");
	}

	private static void sleepUnchecked(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
