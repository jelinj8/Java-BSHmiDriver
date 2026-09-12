package cz.bliksoft.hmieink.protocol;

/**
 * Shared by {@code POWER_STATUS_RESPONSE.LAST_WAKE_REASON} and the handshake's
 * {@code LAST_WAKE_REASON} capability TLV (doc/PROTOCOL.md §17.2, §5.2).
 * Mirrors firmware's {@code Protocol.h} {@code wakeReason} namespace.
 */
public final class WakeReason {

	private WakeReason() {
	}

	public static final int POWER_ON = 0x00;
	public static final int HARD_SLEEP_TIMER = 0x01;
	public static final int HARD_SLEEP_BUTTON = 0x02;
	public static final int HARD_SLEEP_EXTERNAL_RESET = 0x03;
	public static final int LOW_POWER_TIMER = 0x04;
	public static final int LOW_POWER_SERIAL_ACTIVITY = 0x05;
	public static final int LOW_POWER_BLE_ACTIVITY = 0x06;
}
