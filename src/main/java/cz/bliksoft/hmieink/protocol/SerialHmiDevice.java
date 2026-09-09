package cz.bliksoft.hmieink.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@link HmiDevice} over {@link SerialFrameTransport} (doc/PROTOCOL.md §3.3). Depends on {@code
 * jSerialComm}, declared `provided` in this module's pom.xml - only a consumer that references
 * this specific class (not {@link HmiDevice} itself) needs it on the runtime classpath.
 *
 * <p>
 * {@link #connect()} additionally waits for the {@code boot_done} LOG_MESSAGE marker (design note
 * 86) before returning - requested directly after spotting a real timing hazard: opening a *new*
 * Serial connection reboots the board (the CH340 adapter's DTR/RTS auto-reset, see {@link
 * SerialFrameTransport}'s own class doc), so sending a handshake or any other command immediately
 * after the transport-level connect returns risks arriving before firmware has actually finished
 * booting. {@link SerialFrameTransport}'s own fixed settle delay was always just a guess, not a
 * real signal, as its class doc already says ("the real fix [...] is still open") - {@code
 * boot_done} is that real signal: firmware pushes it automatically once its init-macro/boot-macro
 * sequence finishes, on every cold boot, regardless of any custom {@code /init.macro}/{@code
 * /boot.macro} content (appended by firmware's own {@code tryPlayInitMacro()}, not part of the
 * user-editable macro files). This wait is best-effort - it does *not* throw if {@code boot_done}
 * never arrives (e.g. firmware predating this marker); it just falls back to whatever the settle
 * delay already provided, rather than becoming a new way for {@code connect()} to fail.
 */
public final class SerialHmiDevice extends HmiDevice {

	private static final long BOOT_DONE_TIMEOUT_MS = 20_000;
	private static final byte[] BOOT_DONE_MARKER = "boot_done".getBytes(StandardCharsets.UTF_8);

	public SerialHmiDevice(String portDescriptor) {
		super(new SerialFrameTransport(portDescriptor));
	}

	public SerialHmiDevice(String portDescriptor, int baudRate) {
		super(new SerialFrameTransport(portDescriptor, baudRate));
	}

	public SerialHmiDevice(String portDescriptor, int baudRate, int resetSettleDelayMs) {
		super(new SerialFrameTransport(portDescriptor, baudRate, resetSettleDelayMs));
	}

	@Override
	public void connect() throws IOException {
		super.connect();
		try {
			getCommandClient().waitForLogMessage(BOOT_DONE_MARKER, BOOT_DONE_TIMEOUT_MS);
		} catch (IOException e) {
			// best-effort only - see class doc. Falls back to relying on the settle delay alone.
		}
	}
}
