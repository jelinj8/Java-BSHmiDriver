package cz.bliksoft.hmieink.protocol;

import java.io.IOException;

import com.fazecast.jSerialComm.SerialPort;

/**
 * Wired Serial/UART transport (doc/PROTOCOL.md §3.3). Depends on
 * {@code jSerialComm}, declared `provided` in this module's pom.xml (see
 * doc/PROTOCOL.md §19 item 17) - the consuming application must put it on the
 * runtime classpath to use this class.
 *
 * <p>
 * <b>The device resets when the port is opened.</b> On the CH340 USB-serial
 * adapter this board uses (and most other common ESP32 boards' auto-program
 * adapters), opening the port asserts DTR/RTS, which drives the same reset
 * circuit flashing tools use to enter the bootloader - confirmed on real
 * hardware: with the lines left at jSerialComm's post-open default, the device
 * received zero bytes and the connection eventually timed out; explicitly
 * deasserting both ({@link SerialPort#clearDTR()}/{@link SerialPort#clearRTS()}
 * immediately after opening) is what lets it come up and run normally rather
 * than sitting silently in the ROM bootloader. Either way the device reboots,
 * so {@link #connect()} blocks for {@link #resetSettleDelayMs} after opening to
 * let boot + this firmware's self-test output (doc/PROTOCOL.md §20) finish
 * before returning, so callers can safely send a frame immediately afterward.
 *
 * <p>
 * <b>{@link #resetSettleDelayMs} is a fixed guess, not a real boot-complete
 * signal</b> - firmware's boot sequence has grown since this delay was first
 * calibrated (the SSD1683 self-test alone now measures ~6.1s on real hardware,
 * on top of a WiFi connect attempt that can itself take up to its own ~15s
 * timeout if no AP is reachable) and could grow further as more bring-up
 * self-tests are added. {@link #DEFAULT_RESET_SETTLE_DELAY_MS} covers the
 * common case with margin, not that pathological WiFi-unreachable worst case -
 * a caller talking to a board that's slow to associate should pass a larger
 * value explicitly via the 3-arg constructor rather than relying on the
 * default. The real fix (having firmware signal "ready" some other way than a
 * fixed delay) is still open - see next-steps.md.
 *
 * <p>
 * <b>RST and BOOT are themselves controllable over these same two signal
 * lines</b> (doc/PROTOCOL.md design note 71) - the CH340 adapter's classic
 * auto-program circuit wires DTR to the chip's GPIO0 (BOOT) line and RTS to its
 * EN (RST) line, the same lines the board's own physical RST/BOOT buttons pull,
 * and the same convention esptool.py itself relies on. {@link #pressReset()}/
 * {@link #releaseReset()} and {@link #pressBoot()}/{@link #releaseBoot()} drive
 * them directly, as if pressing/releasing those physical buttons in software;
 * {@link #resetToRunMode()} and {@link #resetToBootloader()} compose them into
 * the two sequences that matter in practice. Since GPIO0 doubles as an
 * ordinary, firmware-readable button once running (doc/PROTOCOL.md §11
 * BUTTON_ID.BOOT) - not just a strap pin - calling
 * {@link #pressBoot()}/{@link #releaseBoot()} while the board is already up and
 * running produces a real {@code BUTTON_EVENT} for {@code BUTTON_ID.BOOT},
 * exactly as if the physical button had been pressed; it only selects
 * bootloader entry when combined with a reset, per
 * {@link #resetToBootloader()}.
 */
public final class SerialFrameTransport extends AbstractStreamFrameTransport {

	/**
	 * Default per doc/PROTOCOL.md §3.3 - not negotiated over the wire, both ends
	 * must already agree.
	 */
	public static final int DEFAULT_BAUD_RATE = 115200;

	/**
	 * Covers the common-case boot sequence (self-test + SSD1683 self-test + a
	 * normal-speed WiFi connect + BLE setup) with margin - see the class doc's
	 * caveat about why this isn't, and can't really be, a precise bound.
	 */
	public static final int DEFAULT_RESET_SETTLE_DELAY_MS = 12000;

	private final String portDescriptor;
	private final int baudRate;
	private final int resetSettleDelayMs;

	private volatile SerialPort port;

	public SerialFrameTransport(String portDescriptor) {
		this(portDescriptor, DEFAULT_BAUD_RATE, DEFAULT_RESET_SETTLE_DELAY_MS);
	}

	public SerialFrameTransport(String portDescriptor, int baudRate) {
		this(portDescriptor, baudRate, DEFAULT_RESET_SETTLE_DELAY_MS);
	}

	public SerialFrameTransport(String portDescriptor, int baudRate, int resetSettleDelayMs) {
		this.portDescriptor = portDescriptor;
		this.baudRate = baudRate;
		this.resetSettleDelayMs = resetSettleDelayMs;
	}

	@Override
	public void connect() throws IOException {
		SerialPort p = SerialPort.getCommPort(portDescriptor);
		p.setBaudRate(baudRate);
		p.setNumDataBits(8);
		p.setNumStopBits(SerialPort.ONE_STOP_BIT);
		p.setParity(SerialPort.NO_PARITY);
		// TIMEOUT_READ_BLOCKING intermittently returned -1 (misread as stream EOF) on
		// real
		// hardware (CH340 adapter, Windows) - TIMEOUT_READ_SEMI_BLOCKING with a finite
		// per-call
		// timeout is reliable instead, at the cost of needing
		// RetryingBlockingInputStream to
		// absorb its SerialPortTimeoutException and present a normal blocking stream.
		// See that
		// class's doc for the full story.
		p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 0);
		if (!p.openPort()) {
			throw new IOException("Failed to open serial port " + portDescriptor);
		}
		p.clearDTR();
		p.clearRTS();
		port = p;
		beginReading(new RetryingBlockingInputStream(p.getInputStream()), p.getOutputStream(),
				"crowpanel-serial-reader-" + portDescriptor);
		settle();
	}

	@Override
	public void close() {
		stopReading();
		SerialPort p = port;
		if (p != null) {
			p.closePort();
		}
	}

	/**
	 * A no-op: unlike BLE/TCP, this connection doesn't need re-establishing after
	 * the device reboots on its own - the CH340 (or similar) USB-serial bridge is a
	 * separate chip from the ESP32 being reset, so it stays enumerated and this COM
	 * port stays open throughout. Deliberately does NOT close/reopen the port:
	 * doing so would assert DTR/RTS again (see this class's doc - that's what
	 * resets the chip in the first place), forcing a second, PC-initiated reset.
	 * See {@link FrameTransport#reestablishAfterDeviceReboot()}'s own doc for why
	 * that's actively harmful, not just redundant, confirmed on real hardware.
	 */
	@Override
	public void reestablishAfterDeviceReboot() {
	}

	/**
	 * A few repeated {@link Frame#MAGIC} bytes - long enough to reliably trigger
	 * UART wake hardware.
	 */
	private static final byte[] WAKE_PREAMBLE = { (byte) Frame.MAGIC, (byte) Frame.MAGIC, (byte) Frame.MAGIC,
			(byte) Frame.MAGIC };

	/**
	 * Sends a short preamble of repeated {@link Frame#MAGIC} bytes before the next
	 * real command, as doc/PROTOCOL.md §17.1 recommends when a device may be in
	 * {@code LOW_POWER} (light sleep): UART-wake hardware typically needs a few
	 * edge transitions before the CPU is fully responsive, so the first byte or two
	 * of a real frame sent immediately after triggering a wake may not be reliably
	 * captured. Harmless against an already-awake device too - the existing
	 * frame-resync logic (§3.2/§3.3) already discards leading bytes that don't form
	 * a valid frame. Call this, then send a normal command (e.g.
	 * {@code HANDSHAKE_REQUEST}) as usual - there is no dedicated wake command.
	 */
	public void sendWakePreamble() throws IOException {
		sendRawBytes(WAKE_PREAMBLE);
	}

	/**
	 * Matches esptool.py's own classic-reset timing (~100ms hold, ~50ms
	 * GPIO0-sampling window).
	 */
	private static final int RESET_PULSE_MS = 100;
	private static final int BOOTLOADER_SAMPLE_WINDOW_MS = 50;

	/**
	 * Asserts RTS, pulling the chip's EN line low - the board stays in reset until
	 * {@link #releaseReset()}.
	 */
	public void pressReset() throws IOException {
		requirePort().setRTS();
	}

	/**
	 * Deasserts RTS, releasing EN - the chip boots as soon as this returns (into
	 * firmware or the ROM bootloader, depending on whether {@link #pressBoot()} is
	 * currently held - see {@link #resetToRunMode()}/
	 * {@link #resetToBootloader()}).
	 */
	public void releaseReset() throws IOException {
		requirePort().clearRTS();
	}

	/**
	 * Asserts DTR, pulling the chip's GPIO0 (BOOT) line low - identical to
	 * physically holding the BOOT button down, including producing a real
	 * {@code BUTTON_EVENT} PRESS if firmware is already running.
	 */
	public void pressBoot() throws IOException {
		requirePort().setDTR();
	}

	/**
	 * Deasserts DTR, releasing GPIO0 - identical to releasing the physical BOOT
	 * button.
	 */
	public void releaseBoot() throws IOException {
		requirePort().clearDTR();
	}

	/**
	 * Resets the board and lets it boot normally into firmware - equivalent to a
	 * physical RST button press, released while BOOT stays untouched (so GPIO0
	 * samples HIGH at boot, same as always). Blocks for {@link #resetSettleDelayMs}
	 * afterward, same as {@link #connect()}, so a caller can safely send a frame
	 * right after this returns.
	 */
	public void resetToRunMode() throws IOException {
		pressReset();
		sleepUninterruptibly(RESET_PULSE_MS);
		releaseReset();
		settle();
	}

	/**
	 * Resets the board into its ROM bootloader (download/flashing mode) instead of
	 * firmware - the same DTR+RTS sequence esptool.py itself uses - and, on this
	 * board's auto-program transistor circuit, the exact transition order matters,
	 * not just the steady-state levels reached (an initial hold-both-then-release
	 * attempt reliably failed to actually reset the chip at all, confirmed on real
	 * hardware - see doc/PROTOCOL.md design note 71): BOOT is released *before* the
	 * reset pulse starts, then pressed again at the same instant the reset pulse
	 * ends, so GPIO0 is already LOW by the time EN releases, which is what makes
	 * the chip's boot ROM select the bootloader instead of the flashed application.
	 * <b>Firmware is not running afterward</b> - the framed protocol will not
	 * respond to anything until the board is reset back with
	 * {@link #resetToRunMode()} (or reflashed). Does not block for
	 * {@link #resetSettleDelayMs} - there is no firmware boot sequence to wait out
	 * this time.
	 */
	public void resetToBootloader() throws IOException {
		releaseBoot();
		pressReset();
		sleepUninterruptibly(RESET_PULSE_MS);
		pressBoot();
		releaseReset();
		sleepUninterruptibly(BOOTLOADER_SAMPLE_WINDOW_MS);
		releaseBoot();
	}

	private SerialPort requirePort() throws IOException {
		SerialPort p = port;
		if (p == null) {
			throw new IOException("not connected");
		}
		return p;
	}

	private void settle() throws IOException {
		if (resetSettleDelayMs > 0) {
			sleepUninterruptibly(resetSettleDelayMs);
		}
	}

	private static void sleepUninterruptibly(int millis) throws IOException {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted during a board-control pulse", e);
		}
	}
}
