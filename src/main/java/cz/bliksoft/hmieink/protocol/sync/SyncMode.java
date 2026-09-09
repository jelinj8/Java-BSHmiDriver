package cz.bliksoft.hmieink.protocol.sync;

/** Which side {@link FolderSync} treats as authoritative. */
public enum SyncMode {

	/** The local folder becomes authoritative: the device is made an exact mirror of it. */
	PC_MASTER,

	/** The device is authoritative: the local folder is made an exact mirror of it. */
	DEVICE_MASTER,

	/**
	 * Neither side is authoritative - changes on each side since the last successful sync (tracked
	 * via a local manifest, see {@link FolderSync}) are propagated to the other side; a path changed
	 * differently on both sides since the last sync is a conflict, left untouched on both sides and
	 * reported rather than guessed at.
	 */
	MERGE
}
