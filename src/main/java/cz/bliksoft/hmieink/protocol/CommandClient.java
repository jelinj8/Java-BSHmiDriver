package cz.bliksoft.hmieink.protocol;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Thin stop-and-wait command/response client (doc/PROTOCOL.md §10) wrapping a
 * {@link FrameTransport}: assigns SEQ, sends, blocks for the matching
 * ACK/NACK/direct-response frame, and retries once - same SEQ, per §10 ("a
 * retry resends the same SEQ, enabling dedup at the receiver") - on timeout.
 * {@link #send} is synchronized, matching the protocol's single-message-
 * in-flight-per-direction model: concurrent callers queue rather than
 * interleaving requests.
 *
 * <p>
 * Installs itself as the transport's sole {@link FrameListener}. A response
 * frame is matched to the pending request as follows (mirroring §10 exactly,
 * not just "same SEQ" - a device-initiated event frame, e.g. BUTTON_EVENT,
 * carries its own independent SEQ counter and could coincidentally collide with
 * a pending request's SEQ):
 * <ul>
 * <li>ACK/NACK: correlates via the payload's own REF_SEQ field.
 * <li>A command with an "inherent data response" (§10's list, e.g.
 * HANDSHAKE_REQUEST -&gt; HANDSHAKE_RESPONSE): correlates via the expected
 * response COMMAND_ID <em>and</em> a matching frame SEQ.
 * </ul>
 * Anything that doesn't correlate - because nothing is pending, or because it's
 * a genuine unsolicited push - is forwarded to every registered
 * {@link CommandEventListener} (see {@link #addEventListener}) instead of being
 * dropped.
 */
public final class CommandClient implements Closeable {

	public static final long DEFAULT_TIMEOUT_MILLIS = 5000;

	// doc/PROTOCOL.md §10's enumerated "commands with an inherent data response" -
	// everything else
	// gets a bare ACK/NACK instead.
	private static final Map<Integer, Integer> DIRECT_RESPONSE_COMMAND_IDS = buildDirectResponseMap();

	private final FrameTransport transport;
	private final long defaultTimeoutMillis;
	private final AtomicInteger seqCounter = new AtomicInteger(0);
	private final Object sendLock = new Object();

	// A list, not a single field, so a temporary listener (waitForLogMessage()
	// below) can be added
	// without disturbing whatever the caller already registered for its own
	// purposes (BUTTON_EVENT,
	// GPIO_EVENT, ...) - previously a second setEventListener() call would have
	// silently clobbered
	// the first.
	private final CopyOnWriteArrayList<CommandEventListener> eventListeners = new CopyOnWriteArrayList<>();
	private volatile int pendingSeq = -1;
	private volatile int pendingResponseCommandId = -1;
	private volatile CompletableFuture<Frame> pendingResponse;

	// doc/PROTOCOL.md §10.1: guards both recentLogMessages and logWaiters as one
	// atomic unit, so a
	// LOG_MESSAGE arriving on the reader thread can never land in the gap between a
	// waitForLogMessage() caller checking history and registering to wait live -
	// see that method's
	// own doc for why this matters (a real race, found live: a macro's first entry
	// can echo within
	// microseconds of PLAY_MACRO's own ACK, well before a *later*, separate script
	// line's
	// waitForLogMessage() call gets a chance to start listening).
	private final Object logMessageLock = new Object();
	private static final int LOG_MESSAGE_HISTORY_CAPACITY = 32;
	private final Deque<Frame> recentLogMessages = new ArrayDeque<>();
	private final List<LogWaiter> logWaiters = new ArrayList<>();

	private static final class LogWaiter {
		final Predicate<Frame> matches;
		final CompletableFuture<Void> future;

		LogWaiter(Predicate<Frame> matches, CompletableFuture<Void> future) {
			this.matches = matches;
			this.future = future;
		}
	}

	public CommandClient(FrameTransport transport) {
		this(transport, DEFAULT_TIMEOUT_MILLIS);
	}

	public CommandClient(FrameTransport transport, long defaultTimeoutMillis) {
		this.transport = transport;
		this.defaultTimeoutMillis = defaultTimeoutMillis;
		transport.setListener(new FrameListener() {
			@Override
			public void onFrame(Frame frame) {
				handleFrame(frame);
			}

			@Override
			public void onTransportClosed(IOException cause) {
				CompletableFuture<Frame> pending = pendingResponse;
				if (pending != null) {
					pending.completeExceptionally(cause != null ? cause : new IOException("transport closed"));
				}
			}
		});
	}

	public void connect() throws IOException {
		transport.connect();
	}

	/**
	 * Frames that don't correlate to a pending request (BUTTON_EVENT, GPIO_EVENT,
	 * ...) are delivered here.
	 */
	public void addEventListener(CommandEventListener listener) {
		eventListeners.add(listener);
	}

	public void removeEventListener(CommandEventListener listener) {
		eventListeners.remove(listener);
	}

	/**
	 * Escape hatch for anything not wrapped by this class - e.g.
	 * {@link HmiDevice#otaInstall(byte[], int, byte[], boolean, long, TransferProgressListener)}
	 * uses this to set a transfer-progress listener before a long send.
	 */
	public FrameTransport getTransport() {
		return transport;
	}

	public boolean isConnected() {
		return transport.isConnected();
	}

	/** Sends one command and blocks for its response, using the default timeout. */
	public Frame send(int commandId, byte[] payload) throws IOException {
		return send(commandId, payload, defaultTimeoutMillis);
	}

	/**
	 * @return the ACK frame (empty payload), or the direct response frame (e.g.
	 *         HANDSHAKE_RESPONSE) for a command §10 defines one for
	 * @throws CommandNackException    if the device replied NACK
	 * @throws CommandTimeoutException if no correlated response arrived within the
	 *                                 timeout, even after one retry
	 */
	public Frame send(int commandId, byte[] payload, long timeoutMillis) throws IOException {
		synchronized (sendLock) {
			int seq = seqCounter.getAndUpdate(s -> (s + 1) & 0xFF);
			Frame request = new Frame(commandId, seq, payload);
			int expectedResponseCommandId = DIRECT_RESPONSE_COMMAND_IDS.getOrDefault(commandId, -1);

			Frame response = attempt(request, expectedResponseCommandId, timeoutMillis);
			if (response == null) {
				response = attempt(request, expectedResponseCommandId, timeoutMillis); // one retry, same SEQ (§10)
			}
			if (response == null) {
				throw new CommandTimeoutException(commandId, seq);
			}
			if (response.getCommandId() == CommandId.NACK) {
				throw CommandNackException.fromNackFrame(response);
			}
			return response;
		}
	}

	/**
	 * @return the correlated response, or {@code null} on timeout (never throws for
	 *         a plain timeout).
	 */
	private Frame attempt(Frame request, int expectedResponseCommandId, long timeoutMillis) throws IOException {
		CompletableFuture<Frame> future = new CompletableFuture<>();
		pendingResponse = future;
		pendingSeq = request.getSeq();
		pendingResponseCommandId = expectedResponseCommandId;
		try {
			transport.send(request);
			return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			return null;
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof IOException) {
				throw (IOException) cause;
			}
			throw new IOException(cause);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted while waiting for response", e);
		} finally {
			pendingResponse = null;
			pendingSeq = -1;
			pendingResponseCommandId = -1;
		}
	}

	private void handleFrame(Frame frame) {
		if (frame.getCommandId() == CommandId.LOG_MESSAGE) {
			recordOrDeliverLogMessage(frame);
		}
		CompletableFuture<Frame> pending = pendingResponse;
		if (pending != null && correlates(frame, pendingSeq, pendingResponseCommandId)) {
			pending.complete(frame);
			return;
		}
		for (CommandEventListener listener : eventListeners) {
			listener.onEvent(frame);
		}
	}

	/**
	 * Satisfies the first currently-waiting {@link LogWaiter} whose predicate
	 * matches (if any), else buffers the frame into {@link #recentLogMessages}
	 * (bounded, oldest evicted first) so a *later* {@link #waitForLogMessage} call
	 * can still retroactively catch it.
	 */
	private void recordOrDeliverLogMessage(Frame frame) {
		synchronized (logMessageLock) {
			for (Iterator<LogWaiter> it = logWaiters.iterator(); it.hasNext();) {
				LogWaiter waiter = it.next();
				if (waiter.matches.test(frame)) {
					it.remove();
					waiter.future.complete(null);
					return;
				}
			}
			recentLogMessages.addLast(frame);
			while (recentLogMessages.size() > LOG_MESSAGE_HISTORY_CAPACITY) {
				recentLogMessages.removeFirst();
			}
		}
	}

	/**
	 * Blocks until a {@code LOG_MESSAGE} (doc/PROTOCOL.md §0x0005) whose payload
	 * exactly matches {@code marker} is received, or throws on timeout - requested
	 * directly: "the PC than could wait for receiving that frame (e.g. for timing
	 * or for waiting for macro completion before sending more commands)". A
	 * {@code LOG_MESSAGE} sent live is echoed back immediately (usable as a
	 * round-trip-time probe); one embedded in a recorded macro (via
	 * {@code RECORD_MACRO}/ {@code SAVE_MACRO}) is echoed back whenever that point
	 * in the macro replays, giving a reliable "macro reached/finished this point"
	 * signal that {@code PLAY_MACRO}'s own ACK ("playback started", not "finished",
	 * §18.3) can't provide on its own.
	 *
	 * <p>
	 * First checks a small bounded history of already-received {@code LOG_MESSAGE}s
	 * (consuming the match if found there) before blocking on a fresh one - not
	 * just an optimization: a macro's first entry can echo back within microseconds
	 * of the triggering {@code PLAY_MACRO}'s own ACK, which can easily be *before*
	 * a later, separate call to this method gets a chance to start listening
	 * (confirmed live: a CLI script issuing {@code PLAY_MACRO} then a
	 * {@code WAIT_LOG} line one line later missed the echo entirely without this).
	 * Uses its own dedicated lock/buffer, not {@link #addEventListener} - does not
	 * disturb any listener the caller already registered, and every matching frame
	 * satisfies at most one waiter (consumed, not re-matched).
	 *
	 * @throws IOException if the timeout elapses first, or the transport
	 *                     closes/errors while waiting
	 */
	public void waitForLogMessage(byte[] marker, long timeoutMillis) throws IOException {
		waitForLogMessage(frame -> Arrays.equals(frame.getPayload(), marker), "a LOG_MESSAGE matching the given marker",
				timeoutMillis);
	}

	/**
	 * Like {@link #waitForLogMessage(byte[], long)} but matches <em>any</em>
	 * {@code LOG_MESSAGE} - requested directly for PC-local script control ("wait
	 * for log message frame... with a timeout... any"), e.g. a script line that
	 * just wants to know a macro has echoed anything back yet, without caring which
	 * marker.
	 *
	 * @throws IOException if the timeout elapses first, or the transport
	 *                     closes/errors while waiting
	 */
	public void waitForLogMessage(long timeoutMillis) throws IOException {
		waitForLogMessage(frame -> true, "any LOG_MESSAGE", timeoutMillis);
	}

	private void waitForLogMessage(Predicate<Frame> matches, String description, long timeoutMillis)
			throws IOException {
		CompletableFuture<Void> future = new CompletableFuture<>();
		LogWaiter waiter = new LogWaiter(matches, future);
		synchronized (logMessageLock) {
			Frame alreadySeen = null;
			for (Frame f : recentLogMessages) {
				if (matches.test(f)) {
					alreadySeen = f;
					break;
				}
			}
			if (alreadySeen != null) {
				recentLogMessages.remove(alreadySeen);
				future.complete(null);
			} else {
				logWaiters.add(waiter);
			}
		}
		try {
			future.get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new IOException("timed out after " + timeoutMillis + "ms waiting for " + description);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof IOException) {
				throw (IOException) cause;
			}
			throw new IOException(cause);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted while waiting for " + description, e);
		} finally {
			synchronized (logMessageLock) {
				logWaiters.remove(waiter);
			}
		}
	}

	private static boolean correlates(Frame frame, int expectedSeq, int expectedResponseCommandId) {
		if (frame.getCommandId() == CommandId.ACK || frame.getCommandId() == CommandId.NACK) {
			byte[] payload = frame.getPayload();
			return payload.length >= 1 && (payload[0] & 0xFF) == expectedSeq;
		}
		return frame.getCommandId() == expectedResponseCommandId && frame.getSeq() == expectedSeq;
	}

	private static Map<Integer, Integer> buildDirectResponseMap() {
		Map<Integer, Integer> m = new HashMap<>();
		m.put(CommandId.HANDSHAKE_REQUEST, CommandId.HANDSHAKE_RESPONSE);
		m.put(CommandId.READ_SCREEN, CommandId.SCREEN_DATA);
		m.put(CommandId.FILE_LIST_REQUEST, CommandId.FILE_LIST_RESPONSE);
		m.put(CommandId.FILE_DOWNLOAD_REQUEST, CommandId.FILE_DATA);
		m.put(CommandId.STORAGE_INFO_REQUEST, CommandId.STORAGE_INFO_RESPONSE);
		m.put(CommandId.CONFIG_BACKUP_REQUEST, CommandId.CONFIG_BACKUP_DATA);
		m.put(CommandId.WIFI_STATUS_REQUEST, CommandId.WIFI_STATUS_RESPONSE);
		m.put(CommandId.BLE_STATUS_REQUEST, CommandId.BLE_STATUS_RESPONSE);
		m.put(CommandId.POWER_STATUS_REQUEST, CommandId.POWER_STATUS_RESPONSE);
		m.put(CommandId.OTA_STATUS_REQUEST, CommandId.OTA_STATUS_RESPONSE);
		m.put(CommandId.GPIO_READ_REQUEST, CommandId.GPIO_READ_RESPONSE);
		return Collections.unmodifiableMap(m);
	}

	@Override
	public void close() throws IOException {
		transport.close();
	}
}
