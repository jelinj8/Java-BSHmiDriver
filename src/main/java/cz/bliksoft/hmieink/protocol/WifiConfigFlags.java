package cz.bliksoft.hmieink.protocol;

/**
 * {@code SET_WIFI_CONFIG.FLAGS} bits (doc/PROTOCOL.md §13.2), beyond the shared
 * {@link ConfigFlags#PERSIST}. Mirrors firmware's {@code Protocol.h}
 * {@code wifiConfigFlags} namespace.
 */
public final class WifiConfigFlags {

	private WifiConfigFlags() {
	}

	/** Attempt to (re)connect immediately; 0 = store only. */
	public static final int CONNECT_NOW = 1 << 1;
}
