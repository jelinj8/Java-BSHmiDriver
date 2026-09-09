package cz.bliksoft.hmieink.protocol;

/** {@code BUTTON_EVENT.EVENT_TYPE} (doc/PROTOCOL.md §11). Mirrors firmware's {@code Protocol.h} {@code buttonEventType} namespace. */
public final class ButtonEventType {

	private ButtonEventType() {
	}

	public static final int PRESS = 0x00;
	public static final int RELEASE = 0x01;
	/** Fires once per press, after the firmware-defined hold threshold - not a repeat stream. */
	public static final int LONG_PRESS = 0x02;
	/** Fires immediately after RELEASE whenever that press-release cycle never reached LONG_PRESS. */
	public static final int SHORT_PRESS = 0x03;
}
