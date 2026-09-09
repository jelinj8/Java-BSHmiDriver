package cz.bliksoft.hmieink.protocol;

/**
 * The BLE chunk sub-header (doc/PROTOCOL.md §3.1): CHUNK_INDEX (1 byte) + CHUNK_FLAGS (1 byte),
 * prefixed to each GATT write/notify value used to fragment/reassemble a Logical Frame. Mirrors
 * firmware's {@code Protocol.h} {@code kChunkHeaderSize} / {@code chunkFlags} - keep both in sync.
 */
public final class ChunkHeader {

	private ChunkHeader() {
	}

	public static final int SIZE = 2;
	public static final int MORE_FOLLOWS = 1;
}
