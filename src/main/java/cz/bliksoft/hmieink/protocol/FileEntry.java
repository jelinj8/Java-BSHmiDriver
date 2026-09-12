package cz.bliksoft.hmieink.protocol;

/**
 * One entry of a FILE_LIST_RESPONSE listing (doc/PROTOCOL.md §14.1) - basename
 * only, no path separators, exactly as the wire format defines NAME. See
 * {@link HmiDevice#listFiles}.
 */
public final class FileEntry {

	private final String name;
	private final boolean directory;
	private final long size;

	public FileEntry(String name, boolean directory, long size) {
		this.name = name;
		this.directory = directory;
		this.size = size;
	}

	public String getName() {
		return name;
	}

	public boolean isDirectory() {
		return directory;
	}

	/**
	 * Byte size for a file; 0 for a directory (doc/PROTOCOL.md §14.1 - SIZE is 0
	 * for ENTRY_TYPE=DIR).
	 */
	public long getSize() {
		return size;
	}

	@Override
	public String toString() {
		return (directory ? "DIR  " : "FILE ") + name + (directory ? "" : " (" + size + " bytes)");
	}
}
