package cz.bliksoft.hmieink.protocol;

/**
 * BLE GATT layout (doc/PROTOCOL.md §19). Mirrors firmware's {@code Protocol.h}
 * {@code ble} namespace.
 */
public final class Ble {

	private Ble() {
	}

	public static final String SERVICE_UUID = "748ac078-f79c-4b13-9ead-1a05597acb3f";

	/**
	 * Single characteristic, combined WRITE | WRITE_NR | NOTIFY properties - see
	 * doc/PROTOCOL.md §19.
	 */
	public static final String CHARACTERISTIC_UUID = "01940d49-522c-4b4e-b0c9-3be78c56c960";

	/**
	 * Fallback max single-write size (bytes) before the real {@code MAX_CHUNK_SIZE}
	 * handshake capability (§5.2) is known.
	 */
	public static final int DEFAULT_MAX_CHUNK_SIZE = 20;
}
