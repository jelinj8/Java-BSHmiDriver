package cz.bliksoft.hmieink.protocol;

import cz.bliksoft.javautils.ble.BleAdapter;

/**
 * {@link HmiDevice} over {@link BleFrameTransport} (doc/PROTOCOL.md §3.1). Depends on the sibling
 * {@code BSToolbox-BLE} library, declared `provided` in this module's pom.xml - only a consumer
 * that references this specific class (not {@link HmiDevice} itself) needs it on the runtime
 * classpath. {@code adapter} must be caller-owned and already have scanned for {@code address},
 * exactly like {@link BleFrameTransport}'s own contract (see its class doc).
 */
public final class BleHmiDevice extends HmiDevice {

	public BleHmiDevice(BleAdapter adapter, String address) {
		super(new BleFrameTransport(adapter, address));
	}
}
