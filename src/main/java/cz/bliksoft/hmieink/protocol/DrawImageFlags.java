package cz.bliksoft.hmieink.protocol;

/**
 * DRAW_IMAGE/DRAW_IMAGE_DATA-specific FLAGS bits (doc/PROTOCOL.md §12.7/§12.17)
 * - bits 0-1 remain the shared {@link WriteFlags} (§2.1) meaning; these
 * commands extend the reserved bits with their own. Mirrors firmware's
 * {@code Protocol.h} {@code drawImageFlags} namespace - keep both in sync.
 */
public final class DrawImageFlags {

	private DrawImageFlags() {
	}

	/**
	 * Draws every pixel opaque, ignoring the referenced/embedded {@code .epi}
	 * data's own {@code HAS_MASK}/mask stream if present - the caller's override of
	 * the default mask-respecting behavior, not a property of the {@code .epi} data
	 * itself.
	 */
	public static final int IGNORE_MASK = 1 << 2;
}
