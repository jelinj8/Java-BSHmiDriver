package cz.bliksoft.hmieink.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import cz.bliksoft.javautils.ble.BleException;
import cz.bliksoft.javautils.ble.BlePeripheral;

/**
 * Fragments writes into at-most-{@code maxChunkSize} pieces, each sent as one
 * {@code writeCharacteristic(..., withResponse=true)} call (doc/PROTOCOL.md
 * §3.1) - `BSToolbox-BLE` has no stream abstraction or auto-fragmentation of
 * its own, so this is what gives {@link BleFrameTransport} the same "just a
 * byte stream" shape {@link AbstractStreamFrameTransport} expects (matching
 * TCP/Serial).
 */
final class BleOutputStream extends OutputStream {

	private final BlePeripheral peripheral;
	private final String serviceUuid;
	private final String characteristicUuid;
	private volatile int maxChunkSize;

	BleOutputStream(BlePeripheral peripheral, String serviceUuid, String characteristicUuid, int initialMaxChunkSize) {
		this.peripheral = peripheral;
		this.serviceUuid = serviceUuid;
		this.characteristicUuid = characteristicUuid;
		this.maxChunkSize = initialMaxChunkSize;
	}

	/**
	 * Call once the real value is known from the handshake's MAX_CHUNK_SIZE
	 * capability (doc/PROTOCOL.md §5.2).
	 */
	void setMaxChunkSize(int maxChunkSize) {
		this.maxChunkSize = maxChunkSize;
	}

	@Override
	public void write(int b) throws IOException {
		write(new byte[] { (byte) b }, 0, 1);
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		int chunkSize = Math.max(1, maxChunkSize);
		int offset = off;
		int remaining = len;
		while (remaining > 0) {
			int n = Math.min(chunkSize, remaining);
			byte[] piece = Arrays.copyOfRange(b, offset, offset + n);
			try {
				peripheral.writeCharacteristic(serviceUuid, characteristicUuid, piece, true);
			} catch (BleException e) {
				throw new IOException("BLE characteristic write failed", e);
			}
			offset += n;
			remaining -= n;
		}
	}
}
