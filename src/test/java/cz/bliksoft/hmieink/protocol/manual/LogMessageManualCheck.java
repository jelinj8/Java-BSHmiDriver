package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandEventListener;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.MacroCodec;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.Volume;

/**
 * Manual, real-hardware verification of LOG_MESSAGE (doc/PROTOCOL.md §0x0005) -
 * a bidirectional debugging/synchronization utility requested directly: "we
 * could make the log frame bidirectional, so it could be put inside a macro by
 * the PC and when executed just repeated back - the PC than could wait for
 * receiving that frame (e.g. for timing or for waiting for macro completion
 * before sending more commands)." First confirms a live LOG_MESSAGE gets both a
 * bare ACK and a separate echoed event with the same payload; then records a
 * macro with two LOG_MESSAGE markers around a PAUSE, plays it back, and uses
 * {@link CommandClient#waitForLogMessage} to both (a) know when playback
 * reaches/finishes each marker and (b) measure the elapsed time between them
 * against the PAUSE's own known duration. NOT part of the automated
 * {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.LogMessageManualCheck COM5
 * </pre>
 */
public final class LogMessageManualCheck {

	private static final String MACRO_PATH = "/logtest.macro";
	private static final int PAUSE_MS = 1000;
	// Generous margin around PAUSE_MS - this is confirming "roughly the right
	// ballpark", not
	// asserting sub-millisecond waveform timing accuracy.
	private static final long TIMING_TOLERANCE_MS = 400;
	private static final long WAIT_TIMEOUT_MS = 10_000;

	private static int failures = 0;

	private LogMessageManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: LogMessageManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		CommandClient client = new CommandClient(new SerialFrameTransport(portDescriptor));
		System.out.println("Connecting to " + portDescriptor + "...");
		client.connect();
		try {
			byte[] liveMarker = "live-probe".getBytes(StandardCharsets.UTF_8);
			System.out.println("-> live LOG_MESSAGE round trip");
			// waitForLogMessage() must be armed *before* sending - firmware pushes the echo
			// before
			// it ACKs (pushLogMessage() then ctx.ack()), so calling send() first here would
			// race:
			// the echo could arrive and find no listener registered yet. Not an issue for
			// the
			// intended macro use case below, where PLAY_MACRO's own ACK ("started", not
			// "finished")
			// always leaves plenty of real time to arm the wait before playback actually
			// reaches
			// the marker.
			Exception[] waitError = new Exception[1];
			Thread waiter = new Thread(() -> {
				try {
					client.waitForLogMessage(liveMarker, WAIT_TIMEOUT_MS);
				} catch (Exception e) {
					waitError[0] = e;
				}
			});
			waiter.start();
			Thread.sleep(50); // let the listener actually register before we send
			long t0 = System.currentTimeMillis();
			client.send(CommandId.LOG_MESSAGE, liveMarker);
			System.out.println("   ACKed after " + (System.currentTimeMillis() - t0) + "ms");
			waiter.join(WAIT_TIMEOUT_MS);
			System.out.println("   echo wait finished after " + (System.currentTimeMillis() - t0) + "ms");
			check("live LOG_MESSAGE echoed back", waitError[0] == null && !waiter.isAlive());

			byte[] startMarker = "start".getBytes(StandardCharsets.UTF_8);
			byte[] endMarker = "end".getBytes(StandardCharsets.UTF_8);
			byte[] pausePayload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(PAUSE_MS).array();

			System.out.println("-> RECORD_MACRO");
			send(client, CommandId.RECORD_MACRO, new byte[0]);
			System.out.println("-> LOG_MESSAGE \"start\" (recorded)");
			send(client, CommandId.LOG_MESSAGE, startMarker);
			System.out.println("-> PAUSE " + PAUSE_MS + "ms (recorded)");
			send(client, CommandId.PAUSE, pausePayload);
			System.out.println("-> LOG_MESSAGE \"end\" (recorded)");
			send(client, CommandId.LOG_MESSAGE, endMarker);
			System.out.println("-> SAVE_MACRO " + MACRO_PATH + " to VOLUME=PSRAM (session-only)");
			send(client, CommandId.SAVE_MACRO, pathPayload(MACRO_PATH));

			byte[] savedMacro = download(client, MACRO_PATH);
			List<MacroCodec.Entry> entries = MacroCodec.decode(savedMacro);
			check("recorded exactly 3 entries", entries.size() == 3);

			System.out.println("-> PLAY_MACRO " + MACRO_PATH);
			// Both marker listeners must be armed *before* sending PLAY_MACRO -
			// handlePlayMacro()
			// (main.cpp) calls gMacroPlayer.start() synchronously before ctx.ack(), and
			// stepMacroPlayback() runs later in that same loop() iteration, so the "start"
			// echo can
			// reach the PC within microseconds of PLAY_MACRO's own ACK - easily faster than
			// this
			// thread waking up from send() and only then calling waitForLogMessage(). Same
			// race the
			// live round trip above guards against, just tighter here.
			long[] playStart = { 0 };
			long[] startAt = { -1 };
			long[] endAt = { -1 };
			CompletableFuture<Void> startFuture = new CompletableFuture<>();
			CompletableFuture<Void> endFuture = new CompletableFuture<>();
			CommandEventListener macroListener = frame -> {
				if (frame.getCommandId() != CommandId.LOG_MESSAGE) {
					return;
				}
				if (Arrays.equals(frame.getPayload(), startMarker)) {
					startAt[0] = System.currentTimeMillis() - playStart[0];
					startFuture.complete(null);
				} else if (Arrays.equals(frame.getPayload(), endMarker)) {
					endAt[0] = System.currentTimeMillis() - playStart[0];
					endFuture.complete(null);
				}
			};
			client.addEventListener(macroListener);
			try {
				playStart[0] = System.currentTimeMillis();
				send(client, CommandId.PLAY_MACRO, pathPayload(MACRO_PATH));

				awaitMarker(startFuture, "start");
				System.out.println("   \"start\" marker received at t+" + startAt[0] + "ms");

				awaitMarker(endFuture, "end");
				System.out.println("   \"end\" marker received at t+" + endAt[0] + "ms");
			} finally {
				client.removeEventListener(macroListener);
			}

			long gap = endAt[0] - startAt[0];
			System.out.println("   gap between markers: " + gap + "ms (expected ~" + PAUSE_MS + "ms)");
			check("gap between markers is within " + TIMING_TOLERANCE_MS + "ms of the PAUSE duration",
					Math.abs(gap - PAUSE_MS) <= TIMING_TOLERANCE_MS);

			System.out.println("-> FILE_DELETE " + MACRO_PATH + " (cleanup)");
			send(client, CommandId.FILE_DELETE, pathPayload(MACRO_PATH));

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

	private static void awaitMarker(CompletableFuture<Void> future, String label) throws Exception {
		try {
			future.get(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new java.io.IOException(
					"timed out after " + WAIT_TIMEOUT_MS + "ms waiting for the \"" + label + "\" marker");
		}
	}

	private static byte[] pathPayload(String path) {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.PSRAM);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		return payload.array();
	}

	private static byte[] download(CommandClient client, String path) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.PSRAM);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		byte[] data = client.send(CommandId.FILE_DOWNLOAD_REQUEST, payload.array()).getPayload();
		long fileLen = readU32LE(data, 0);
		byte[] out = new byte[(int) fileLen];
		System.arraycopy(data, 4, out, 0, (int) fileLen);
		return out;
	}

	private static long readU32LE(byte[] data, int offset) {
		return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8) | ((data[offset + 2] & 0xFFL) << 16)
				| ((data[offset + 3] & 0xFFL) << 24);
	}

	private static void send(CommandClient client, int commandId, byte[] payload) throws Exception {
		try {
			client.send(commandId, payload);
		} catch (CommandNackException e) {
			System.err.println("FAILED: commandId=0x" + Integer.toHexString(commandId) + " NACK status=0x"
					+ Integer.toHexString(e.getStatus()));
			System.exit(1);
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
