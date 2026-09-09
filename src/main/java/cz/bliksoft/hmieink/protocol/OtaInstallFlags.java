package cz.bliksoft.hmieink.protocol;

/** {@code OTA_INSTALL.FLAGS} bits (doc/PROTOCOL.md §16.1). Mirrors firmware's {@code Protocol.h} {@code otaInstallFlags} namespace. */
public final class OtaInstallFlags {

	private OtaInstallFlags() {
	}

	/** Reboot into the new image immediately once written and verified; 0 = stage it for a later OTA_APPLY. */
	public static final int APPLY_NOW = 1;
}
