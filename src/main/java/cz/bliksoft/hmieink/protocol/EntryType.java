package cz.bliksoft.hmieink.protocol;

/**
 * FILE_LIST_RESPONSE's ENTRY_TYPE byte (doc/PROTOCOL.md §14.1). Mirrors firmware's
 * {@code Protocol.h} {@code entryType} namespace - keep both in sync.
 */
public final class EntryType {

	private EntryType() {
	}

	public static final int FILE = 0x00;
	public static final int DIRECTORY = 0x01;
}
