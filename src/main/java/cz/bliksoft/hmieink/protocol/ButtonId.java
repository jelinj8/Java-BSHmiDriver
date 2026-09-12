package cz.bliksoft.hmieink.protocol;

/**
 * {@code BUTTON_EVENT.BUTTON_ID} (doc/PROTOCOL.md §11). Mirrors firmware's
 * {@code Protocol.h} {@code buttonId} namespace.
 */
public final class ButtonId {

	private ButtonId() {
	}

	public static final int UNKNOWN = 0x00;
	/** Dial switch press/confirm. */
	public static final int DIAL_SWITCH = 0x01;
	public static final int MENU = 0x02;
	public static final int BACK = 0x03;
	/**
	 * Not emitted by this firmware - BOOT is a hardware strap pin, not observable
	 * as a button event.
	 */
	public static final int BOOT = 0x04;
	/**
	 * Not emitted by this firmware - RESET is a hardware EN pin, not observable as
	 * a button event.
	 */
	public static final int RESET = 0x05;
	public static final int DIAL_UP = 0x06;
	public static final int DIAL_DOWN = 0x07;
	/**
	 * {@code GENERIC_BUTTON_0} - base of a 16-value range (0x10-0x1F) for unlabeled
	 * GPIO buttons on other boards.
	 */
	public static final int GENERIC_BUTTON_0 = 0x10;
}
