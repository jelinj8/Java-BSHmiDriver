package cz.bliksoft.hmieink.protocol;

/**
 * {@code GPIO_CONFIGURE.FLAGS} bits (doc/PROTOCOL.md §15.1). Mirrors firmware's
 * {@code Protocol.h} {@code gpioConfigureFlags} namespace.
 */
public final class GpioConfigureFlags {

	private GpioConfigureFlags() {
	}

	/**
	 * Only meaningful for the INPUT* modes: device pushes GPIO_EVENT on every
	 * logical level change.
	 */
	public static final int ENABLE_CHANGE_EVENTS = 1;
}
