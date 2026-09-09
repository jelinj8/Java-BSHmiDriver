package cz.bliksoft.hmieink.protocol;

/** Thrown when a byte array does not decode into a valid {@link Frame} (bad MAGIC, length mismatch, or CRC failure). */
public class FrameException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public FrameException(String message) {
		super(message);
	}
}
