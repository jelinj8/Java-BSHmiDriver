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

	/**
	 * Static IPv4 setup instead of DHCP - IP/NETMASK/GATEWAY/DNS follow FLAGS; 0 =
	 * DHCP (with PERSIST also clearing a stored static setup).
	 */
	public static final int STATIC_IP = 1 << 2;
}
