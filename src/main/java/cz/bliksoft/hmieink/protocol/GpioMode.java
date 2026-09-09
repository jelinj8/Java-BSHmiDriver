package cz.bliksoft.hmieink.protocol;

/** {@code GPIO_CONFIGURE.MODE} (doc/PROTOCOL.md §15.1). Mirrors firmware's {@code Protocol.h} {@code gpioMode} namespace. */
public final class GpioMode {

	private GpioMode() {
	}

	public static final int INPUT = 0x00;
	public static final int INPUT_PULLUP = 0x01;
	public static final int INPUT_PULLDOWN = 0x02;
	public static final int OUTPUT = 0x03;
	/** Reserved, not implemented by firmware yet - a GPIO_CONFIGURE using this MODE gets NACK(BAD_PARAMETERS). */
	public static final int PWM_OUTPUT = 0x04;
	/** Reserved, not implemented by firmware yet - a GPIO_CONFIGURE using this MODE gets NACK(BAD_PARAMETERS). */
	public static final int ANALOG_INPUT = 0x05;
}
