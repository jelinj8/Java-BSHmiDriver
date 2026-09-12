package cz.bliksoft.hmieink.protocol;

/**
 * {@code GPIO_PLAY_PATTERN.FLAGS} bits (doc/PROTOCOL.md §15.5). Mirrors
 * firmware's {@code Protocol.h} {@code gpioPatternFlags} namespace.
 */
public final class GpioPatternFlags {

	private GpioPatternFlags() {
	}

	public static final int INITIAL_LEVEL_HIGH = 1;
	public static final int REPEAT_FOREVER = 1 << 1;
}
