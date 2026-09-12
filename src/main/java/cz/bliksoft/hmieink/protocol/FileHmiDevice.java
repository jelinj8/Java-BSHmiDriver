package cz.bliksoft.hmieink.protocol;

/**
 * {@link HmiDevice} over {@link FileFrameTransport} - "generate a macro
 * locally" without a live device. No {@code provided} dependency. Call
 * {@link #close()} to actually write the accumulated commands to {@code path}
 * as a {@code .macro} file (§18).
 */
public final class FileHmiDevice extends HmiDevice {

	public FileHmiDevice(String path) {
		super(new FileFrameTransport(path));
	}
}
