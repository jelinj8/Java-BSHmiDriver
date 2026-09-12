package cz.bliksoft.hmieink.protocol;

/**
 * {@code SET_POWER_MODE.MODE} values (doc/PROTOCOL.md §17.1). Mirrors
 * firmware's {@code Protocol.h} {@code powerMode} namespace.
 */
public final class PowerMode {

	private PowerMode() {
	}

	public static final int ACTIVE = 0x00;
	public static final int LOW_POWER = 0x01;
	public static final int HARD_SLEEP = 0x02;

	/** {@code SET_POWER_MODE.FLAGS} bit, LOW_POWER only. */
	public static final int FLAG_KEEP_BLE_CONNECTABLE = 1;
}
