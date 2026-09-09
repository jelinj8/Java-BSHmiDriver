package cz.bliksoft.hmieink.protocol;

/** Shared {@code FLAGS.PERSIST} bit (doc/PROTOCOL.md §13.2/§13.3) - every {@code SET_*} config command uses it. Mirrors firmware's {@code Protocol.h} {@code configFlags} namespace. */
public final class ConfigFlags {

	private ConfigFlags() {
	}

	/** Save to NVS, becomes the power-on default; 0 = this boot only. */
	public static final int PERSIST = 1;
}
