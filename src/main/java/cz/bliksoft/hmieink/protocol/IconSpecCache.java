package cz.bliksoft.hmieink.protocol;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

import cz.bliksoft.javautils.images.iconspec.IconSpecEngine;

/**
 * Cache for images generated from icon specs, encoded in the .epi format for
 * the HMI display protocol.
 * <p>
 * Icon specs are processed by {@link IconSpecEngine}, and the resulting images
 * are converted to the binary B/W format used by the device (1 bit per pixel,
 * black/white) via {@link EpiImageCodec}. Cached images can be referenced from
 * subsequent commands' {@code BYTES} fields using a {@code #cache-name} token -
 * {@code TextCommandFormat}'s field parsing resolves it via {@link #get}, the
 * same way {@code @<file>} reads raw bytes from a file.
 * <p>
 * This class is decoupled from the common-java-utils dependency - it uses
 * {@link IconSpecEngine} only when needed, and gracefully handles its absence
 * by throwing {@link UnsupportedOperationException} if the dependency is not on
 * the classpath.
 * <p>
 * Additional dependencies that may be needed for specific icon spec features:
 * <ul>
 * <li>{@code com.github.weisj:jsvg} - for SVG processing</li>
 * <li>zxing - for QR code generation</li>
 * </ul>
 *
 * @see IconSpecEngine
 * @see EpiImageCodec
 */
public final class IconSpecCache {

	private static final Map<String, byte[]> cache = new HashMap<>();
	private static volatile String brandingImagesRoot = null;
	private static volatile Boolean iconSpecAvailable = null;

	private IconSpecCache() {
	}

	/**
	 * Sets the root directory for resolving relative image paths in icon specs.
	 *
	 * @param path the new root path (can be a file system path or classpath root)
	 */
	public static void setBrandingImagesRoot(String path) {
		brandingImagesRoot = path;
	}

	/**
	 * Generates an image from an icon spec string, converts it to the .epi format
	 * (binary B/W with RLE compression), and stores it in the cache.
	 * <p>
	 * If the source image has transparency (alpha values below 128), a transparency
	 * mask is included in the .epi output.
	 *
	 * @param name the cache key under which to store the image
	 * @param spec the icon spec string to process
	 * @return the encoded .epi image data
	 * @throws UnsupportedOperationException if the common-java-utils dependency is
	 *                                       not on the classpath
	 * @throws IllegalArgumentException      if the spec cannot be processed
	 */
	public static byte[] generateAndCache(String name, String spec) {
		if (!isAvailable()) {
			throw new UnsupportedOperationException(
					"IconSpecCache requires the common-java-utils library (cz.bliksoft.java:common-java-utils) on the classpath");
		}
		if (brandingImagesRoot != null) {
			IconSpecEngine.setBrandingImagesRoot(brandingImagesRoot);
		}
		BufferedImage img = IconSpecEngine.createImage(spec);
		if (img == null) {
			throw new IllegalArgumentException("Failed to generate image from spec: " + spec);
		}
		boolean hasMask = EpiImageCodec.hasTransparency(img);
		byte[] epi = EpiImageCodec.encode(img, hasMask);
		cache.put(name, epi);
		return epi;
	}

	/**
	 * Retrieves a previously cached image in .epi format.
	 *
	 * @param name the cache key
	 * @return the encoded .epi image data, or {@code null} if not cached
	 */
	public static byte[] get(String name) {
		return cache.get(name);
	}

	/**
	 * Checks if the IconSpecCache functionality is available (common-java-utils on
	 * classpath). Result is cached after first check.
	 *
	 * @return {@code true} if the dependency is available
	 */
	public static boolean isAvailable() {
		if (iconSpecAvailable == null) {
			try {
				Class.forName("cz.bliksoft.javautils.images.iconspec.IconSpecEngine");
				iconSpecAvailable = true;
			} catch (ClassNotFoundException e) {
				iconSpecAvailable = false;
			}
		}
		return iconSpecAvailable;
	}

	/**
	 * Clears all cached images.
	 */
	public static void clear() {
		cache.clear();
	}

	/**
	 * Resets the cached availability check. Useful for testing.
	 */
	static void resetAvailabilityCache() {
		iconSpecAvailable = null;
	}

	/**
	 * Puts raw {@code .epi} bytes into the cache directly, bypassing
	 * {@link IconSpecEngine}. Useful for testing {@code #name} resolution without a
	 * real icon spec.
	 */
	static void put(String name, byte[] epi) {
		cache.put(name, epi);
	}
}
