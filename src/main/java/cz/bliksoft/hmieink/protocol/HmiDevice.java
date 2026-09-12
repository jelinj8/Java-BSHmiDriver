package cz.bliksoft.hmieink.protocol;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cz.bliksoft.hmieink.protocol.schema.CommandSchema;
import cz.bliksoft.hmieink.protocol.schema.PayloadCodec;
import cz.bliksoft.hmieink.protocol.text.TextCommandFormat;

/**
 * High-level device-client facade (plan.md Phase 5) tying together a transport,
 * the handshake, and command dispatch - one typed method per sendable command
 * (doc/PROTOCOL.md §5-§18), plus {@link #sendText}/{@link #describe} for the
 * plaintext command notation the CLI uses.
 *
 * <p>
 * <b>Deliberately transport-agnostic on its own</b> - this class never
 * references {@link SerialFrameTransport}, {@link BleFrameTransport}, or
 * {@code BleAdapter}. Those are the classes that actually pull in this module's
 * two {@code provided} dependencies (jSerialComm, BSToolbox-BLE) - if
 * {@code HmiDevice} referenced them directly, even just inside a static factory
 * method, any consumer touching this class at all (including one who only ever
 * uses TCP or {@link FileFrameTransport}) would force both jars onto the
 * runtime classpath just to satisfy the JVM's verification of this class.
 * {@link TcpHmiDevice}/{@link SerialHmiDevice}/{@link BleHmiDevice}/{@link FileHmiDevice}
 * isolate that, one per transport, mirroring the isolation the project already
 * relies on for the transports themselves.
 */
public class HmiDevice implements Closeable {

	protected final CommandClient commandClient;

	public HmiDevice(FrameTransport transport) {
		this(transport, CommandClient.DEFAULT_TIMEOUT_MILLIS);
	}

	public HmiDevice(FrameTransport transport, long defaultTimeoutMillis) {
		this.commandClient = new CommandClient(transport, defaultTimeoutMillis);
	}

	public void connect() throws IOException {
		commandClient.connect();
	}

	public boolean isConnected() {
		return commandClient.isConnected();
	}

	@Override
	public void close() throws IOException {
		commandClient.close();
	}

	/** Escape hatch for anything not (yet) wrapped by a typed method below. */
	public CommandClient getCommandClient() {
		return commandClient;
	}

	public void addEventListener(CommandEventListener listener) {
		commandClient.addEventListener(listener);
	}

	public void removeEventListener(CommandEventListener listener) {
		commandClient.removeEventListener(listener);
	}

	// --- plaintext command notation (doc/PROTOCOL.md-external, see
	// TextCommandFormat) ---

	/**
	 * Parses and sends one {@code NAME|field|field|...} line (default separator
	 * {@code |}).
	 */
	public Frame sendText(String line) throws IOException {
		return sendText(line, '|');
	}

	public Frame sendText(String line, char separator) throws IOException {
		TextCommandFormat.ParsedCommand parsed = TextCommandFormat.parse(line, separator);
		return commandClient.send(parsed.commandId, parsed.payload);
	}

	/**
	 * Renders a frame (e.g. a live event, or one entry from a decoded
	 * {@code .macro} file) back to text.
	 */
	public String describe(Frame frame) {
		return describe(frame.getCommandId(), frame.getPayload(), '|');
	}

	public String describe(int commandId, byte[] payload) {
		return describe(commandId, payload, '|');
	}

	public String describe(int commandId, byte[] payload, char separator) {
		return TextCommandFormat.format(commandId, payload, separator);
	}

	// --- core / handshake (§5, §10.1) ---

	public HandshakeCapabilities handshake() throws IOException {
		return handshake(AuthLevel.NONE, "");
	}

	/**
	 * doc/PROTOCOL.md §5.3: {@code pinType} is {@link AuthLevel#USAGE} or
	 * {@link AuthLevel#ADMIN}.
	 */
	public HandshakeCapabilities handshake(int pinType, String pin) throws IOException {
		Frame response = send(CommandId.HANDSHAKE_REQUEST,
				fields("PIN_TYPE", (long) pinType, "PIN", pin != null ? pin : ""));
		return HandshakeCapabilities.parse(response.getPayload());
	}

	public void logMessage(byte[] marker) throws IOException {
		send(CommandId.LOG_MESSAGE, fields("MARKER", marker));
	}

	public void logMessage(String marker) throws IOException {
		logMessage(marker.getBytes(StandardCharsets.UTF_8));
	}

	// --- image transfer / screen readback / degauss (§6-§9) ---

	public void fullImageTransfer(byte[] rawBitmap, int flags) throws IOException {
		send(CommandId.FULL_IMAGE_TRANSFER, fields("FLAGS", (long) flags, "DATA", rawBitmap));
	}

	public void partialImageTransfer(int x, int y, int width, int height, byte[] rawBitmap, int flags)
			throws IOException {
		send(CommandId.PARTIAL_IMAGE_TRANSFER, fields("X", (long) x, "Y", (long) y, "WIDTH", (long) width, "HEIGHT",
				(long) height, "FLAGS", (long) flags, "DATA", rawBitmap));
	}

	public Map<String, Object> readScreen(int source, int mode, int x, int y, int width, int height)
			throws IOException {
		return sendAndDecode(CommandId.READ_SCREEN, CommandId.SCREEN_DATA, fields("SOURCE", (long) source, "MODE",
				(long) mode, "X", (long) x, "Y", (long) y, "WIDTH", (long) width, "HEIGHT", (long) height));
	}

	public Map<String, Object> readScreenFull(int source) throws IOException {
		return readScreen(source, ReadScreenMode.FULL, 0, 0, 0, 0);
	}

	public void clearArtifacts(int cycles, int flags) throws IOException {
		send(CommandId.CLEAR_ARTIFACTS, fields("CYCLES", (long) cycles, "FLAGS", (long) flags));
	}

	// --- local drawing primitives (§12) ---

	public void drawLine(int x0, int y0, int x1, int y1, int color, int drawMode, int lineWidth, int flags)
			throws IOException {
		send(CommandId.DRAW_LINE, fields("X0", (long) x0, "Y0", (long) y0, "X1", (long) x1, "Y1", (long) y1, "COLOR",
				(long) color, "DRAW_MODE", (long) drawMode, "LINE_WIDTH", (long) lineWidth, "FLAGS", (long) flags));
	}

	public void drawRect(int x, int y, int width, int height, int color, int drawMode, boolean filled, int lineWidth,
			int flags) throws IOException {
		send(CommandId.DRAW_RECT,
				fields("X", (long) x, "Y", (long) y, "WIDTH", (long) width, "HEIGHT", (long) height, "COLOR",
						(long) color, "DRAW_MODE", (long) drawMode, "FILLED", bool(filled), "LINE_WIDTH",
						(long) lineWidth, "FLAGS", (long) flags));
	}

	public void drawCircle(int centerX, int centerY, int radius, int color, int drawMode, boolean filled, int lineWidth,
			int flags) throws IOException {
		send(CommandId.DRAW_CIRCLE,
				fields("CENTER_X", (long) centerX, "CENTER_Y", (long) centerY, "RADIUS", (long) radius, "COLOR",
						(long) color, "DRAW_MODE", (long) drawMode, "FILLED", bool(filled), "LINE_WIDTH",
						(long) lineWidth, "FLAGS", (long) flags));
	}

	public void clearRegion(int x, int y, int width, int height, int color, int flags) throws IOException {
		send(CommandId.CLEAR_REGION, fields("X", (long) x, "Y", (long) y, "WIDTH", (long) width, "HEIGHT",
				(long) height, "COLOR", (long) color, "FLAGS", (long) flags));
	}

	public void drawText(int x, int y, int width, int fontId, int color, int background, int drawMode, int align,
			boolean wrap, int flags, String text) throws IOException {
		send(CommandId.DRAW_TEXT,
				fields("X", (long) x, "Y", (long) y, "WIDTH", (long) width, "FONT_ID", (long) fontId, "COLOR",
						(long) color, "BACKGROUND", (long) background, "DRAW_MODE", (long) drawMode, "ALIGN",
						(long) align, "WRAP", bool(wrap), "FLAGS", (long) flags, "TEXT", text));
	}

	/**
	 * DRAW_TEXT with FONT_ID={@link FontId#CUSTOM}, sending {@code rawGlyphIndices}
	 * verbatim as TEXT rather than going through {@link #drawText}'s UTF-8 string
	 * encoding. The custom font's TEXT is a raw single-byte codepage, not UTF-8
	 * (doc/PROTOCOL.md §12.6.1): each byte 0x00-0xFF is directly a glyph index, and
	 * bytes >=0x80 must round-trip unmodified, which the schema's UTF-8 STRING
	 * field kind cannot guarantee (only 0x00-0x7F survive UTF-8 re-encoding
	 * unchanged) - so this hand-builds the payload instead of going through
	 * {@link CommandSchema}/{@link PayloadCodec} for TEXT, mirroring the manual
	 * DRAW_TEXT payload pattern used in this project's hardware-verification tools.
	 */
	public void drawTextCustom(int x, int y, int width, int color, int background, int drawMode, int align,
			boolean wrap, int flags, byte[] rawGlyphIndices) throws IOException {
		byte[] text = rawGlyphIndices != null ? rawGlyphIndices : new byte[0];
		ByteBuffer payload = ByteBuffer.allocate(15 + text.length).order(ByteOrder.LITTLE_ENDIAN);
		payload.putShort((short) x);
		payload.putShort((short) y);
		payload.putShort((short) width);
		payload.put((byte) FontId.CUSTOM);
		payload.put((byte) color);
		payload.put((byte) background);
		payload.put((byte) drawMode);
		payload.put((byte) align);
		payload.put((byte) (wrap ? 1 : 0));
		payload.put((byte) flags);
		payload.putShort((short) text.length);
		payload.put(text);
		commandClient.send(CommandId.DRAW_TEXT, payload.array());
	}

	/**
	 * Convenience overload encoding {@code text} via {@code charset} (e.g.
	 * {@link StandardCharsets#ISO_8859_1} to reach codepage bytes 0x80-0xFF through
	 * ordinary String literals, or any custom {@link Charset} matching a particular
	 * glyph folder's own codepage layout) before delegating to
	 * {@link #drawTextCustom(int, int, int, int, int, int, int, boolean, int, byte[])}.
	 */
	public void drawTextCustom(int x, int y, int width, int color, int background, int drawMode, int align,
			boolean wrap, int flags, String text, Charset charset) throws IOException {
		drawTextCustom(x, y, width, color, background, drawMode, align, wrap, flags,
				text != null ? text.getBytes(charset) : new byte[0]);
	}

	public void drawImage(int x, int y, int drawMode, int flags, int volume, String path) throws IOException {
		send(CommandId.DRAW_IMAGE, fields("X", (long) x, "Y", (long) y, "DRAW_MODE", (long) drawMode, "FLAGS",
				(long) flags, "VOLUME", (long) volume, "PATH", path));
	}

	public void refresh(int mode) throws IOException {
		send(CommandId.REFRESH, fields("MODE", (long) mode));
	}

	public void shiftRegion(int x, int y, int width, int height, int direction, int step, int fillColor, int flags)
			throws IOException {
		send(CommandId.SHIFT_REGION,
				fields("X", (long) x, "Y", (long) y, "WIDTH", (long) width, "HEIGHT", (long) height, "DIRECTION",
						(long) direction, "STEP", (long) step, "FILL_COLOR", (long) fillColor, "FLAGS", (long) flags));
	}

	public void setClipRegion(int x, int y, int width, int height) throws IOException {
		send(CommandId.SET_CLIP_REGION,
				fields("X", (long) x, "Y", (long) y, "WIDTH", (long) width, "HEIGHT", (long) height));
	}

	public void resetClipRegion() throws IOException {
		setClipRegion(0, 0, 0, 0);
	}

	public void copyRegion(int srcX, int srcY, int dstX, int dstY, int width, int height, int flags)
			throws IOException {
		send(CommandId.COPY_REGION, fields("SRC_X", (long) srcX, "SRC_Y", (long) srcY, "DST_X", (long) dstX, "DST_Y",
				(long) dstY, "WIDTH", (long) width, "HEIGHT", (long) height, "FLAGS", (long) flags));
	}

	public void setDrawOffset(int dx, int dy) throws IOException {
		send(CommandId.SET_DRAW_OFFSET, fields("DX", (long) dx, "DY", (long) dy));
	}

	public void setOrientation(int rotation, int flags) throws IOException {
		send(CommandId.SET_ORIENTATION, fields("ROTATION", (long) rotation, "FLAGS", (long) flags));
	}

	/**
	 * Points {@link FontId#CUSTOM}'s glyph folder (doc/PROTOCOL.md §12.6.2) at
	 * {@code path}, which must carry a mandatory "R:"/"S:"/"F:" volume prefix -
	 * "S:"/"F:" select the folder directly on SD/INTERNAL, "R:" resolves a PSRAM
	 * pointer file's content (itself "S:"/"F:"-prefixed) as the real folder,
	 * exactly once, at the moment this command runs. Usage-level, session-only - no
	 * persist option exists for this command.
	 */
	public void setCustomFontFolder(String path) throws IOException {
		send(CommandId.SET_CUSTOM_FONT_FOLDER, fields("PATH", path));
	}

	public void drawImageRow(int x, int y, int width, int align, int spacing, int drawMode, int flags, int volume,
			String... paths) throws IOException {
		send(CommandId.DRAW_IMAGE_ROW,
				fields("X", (long) x, "Y", (long) y, "WIDTH", (long) width, "ALIGN", (long) align, "SPACING",
						(long) spacing, "DRAW_MODE", (long) drawMode, "FLAGS", (long) flags, "VOLUME", (long) volume,
						"PATHS", Arrays.asList(paths)));
	}

	public void fillImage(int x, int y, int width, int height, int tileMode, int drawMode, int flags, int volume,
			String path) throws IOException {
		send(CommandId.FILL_IMAGE,
				fields("X", (long) x, "Y", (long) y, "WIDTH", (long) width, "HEIGHT", (long) height, "TILE_MODE",
						(long) tileMode, "DRAW_MODE", (long) drawMode, "FLAGS", (long) flags, "VOLUME", (long) volume,
						"PATH", path));
	}

	public void fastClear(int color, int flags) throws IOException {
		send(CommandId.FAST_CLEAR, fields("COLOR", (long) color, "FLAGS", (long) flags));
	}

	// --- configuration (§13) ---

	public Map<String, Object> configBackup() throws IOException {
		Frame response = commandClient.send(CommandId.CONFIG_BACKUP_REQUEST, new byte[0]);
		return PayloadCodec.decode(CommandSchema.byId(CommandId.CONFIG_BACKUP_DATA), response.getPayload());
	}

	public void configRestore(int configVersion, byte[] tlvData) throws IOException {
		send(CommandId.CONFIG_RESTORE, fields("CONFIG_VERSION", (long) configVersion, "TLV_DATA", tlvData));
	}

	public void setWifiConfig(String ssid, String password, boolean persist, boolean connectNow) throws IOException {
		long flags = (persist ? ConfigFlags.PERSIST : 0) | (connectNow ? WifiConfigFlags.CONNECT_NOW : 0);
		send(CommandId.SET_WIFI_CONFIG, fields("SSID", ssid, "PASSWORD", password, "FLAGS", flags));
	}

	public Map<String, Object> wifiStatus() throws IOException {
		return sendAndDecode(CommandId.WIFI_STATUS_REQUEST, CommandId.WIFI_STATUS_RESPONSE, fields());
	}

	public void setWifiEnabled(boolean enabled, boolean persist) throws IOException {
		send(CommandId.SET_WIFI_ENABLED, fields("ENABLED", bool(enabled), "FLAGS", persistFlag(persist)));
	}

	public void setBleEnabled(boolean enabled, boolean persist) throws IOException {
		send(CommandId.SET_BLE_ENABLED, fields("ENABLED", bool(enabled), "FLAGS", persistFlag(persist)));
	}

	public void setBlePin(Integer pin, boolean persist) throws IOException {
		send(CommandId.SET_BLE_PIN, fields("HAS_PIN", bool(pin != null), "PIN", (long) (pin != null ? pin : 0), "FLAGS",
				persistFlag(persist)));
	}

	public Map<String, Object> bleStatus() throws IOException {
		return sendAndDecode(CommandId.BLE_STATUS_REQUEST, CommandId.BLE_STATUS_RESPONSE, fields());
	}

	public void setDeviceName(String name, boolean persist) throws IOException {
		send(CommandId.SET_DEVICE_NAME, fields("NAME", name != null ? name : "", "FLAGS", persistFlag(persist)));
	}

	/**
	 * doc/PROTOCOL.md §5.3 - admin-gated (setting the usage PIN itself requires
	 * admin).
	 */
	public void setUsagePin(String pin, boolean persist) throws IOException {
		send(CommandId.SET_USAGE_PIN,
				fields("HAS_PIN", bool(pin != null), "PIN", pin != null ? pin : "", "FLAGS", persistFlag(persist)));
	}

	public void setAdminPin(String pin, boolean persist) throws IOException {
		send(CommandId.SET_ADMIN_PIN,
				fields("HAS_PIN", bool(pin != null), "PIN", pin != null ? pin : "", "FLAGS", persistFlag(persist)));
	}

	// --- storage (§14) ---

	public Map<String, Object> fileList(int volume, String path) throws IOException {
		return sendAndDecode(CommandId.FILE_LIST_REQUEST, CommandId.FILE_LIST_RESPONSE,
				fields("VOLUME", (long) volume, "PATH", path));
	}

	/**
	 * Timeout-overriding form of {@link #fileList} - listing a large directory
	 * (hundreds of entries) on real SD hardware can genuinely take longer than
	 * {@link CommandClient#DEFAULT_TIMEOUT_MILLIS}, since every {@code VOLUME=SD}
	 * operation walks the FAT directory over SPI (§14);
	 * {@link cz.bliksoft.hmieink.protocol.sync.FolderSync} uses this for exactly
	 * that reason.
	 */
	public Map<String, Object> fileList(int volume, String path, long timeoutMillis) throws IOException {
		return sendAndDecode(CommandId.FILE_LIST_REQUEST, CommandId.FILE_LIST_RESPONSE,
				fields("VOLUME", (long) volume, "PATH", path), timeoutMillis);
	}

	/**
	 * Typed, itemized form of {@link #fileList} - {@link CommandSchema} leaves
	 * FILE_LIST_RESPONSE's repeated ENTRIES as one opaque blob (no generic
	 * repeated-compound-record support), so this parses it by hand: NAME_LEN(u8)
	 * NAME ENTRY_TYPE(u8) SIZE(u32LE), repeated ENTRY_COUNT times (doc/PROTOCOL.md
	 * §14.1).
	 */
	public List<FileEntry> listFiles(int volume, String path) throws IOException {
		return parseFileEntries(fileList(volume, path));
	}

	/**
	 * Timeout-overriding form of {@link #listFiles} - see
	 * {@link #fileList(int, String, long)}.
	 */
	public List<FileEntry> listFiles(int volume, String path, long timeoutMillis) throws IOException {
		return parseFileEntries(fileList(volume, path, timeoutMillis));
	}

	private static List<FileEntry> parseFileEntries(Map<String, Object> decoded) {
		byte[] entries = (byte[]) decoded.get("ENTRIES");
		long entryCount = (Long) decoded.get("ENTRY_COUNT");
		List<FileEntry> result = new ArrayList<>((int) entryCount);
		int pos = 0;
		for (long i = 0; i < entryCount; i++) {
			int nameLen = entries[pos] & 0xFF;
			pos += 1;
			String name = new String(entries, pos, nameLen, StandardCharsets.UTF_8);
			pos += nameLen;
			boolean directory = (entries[pos] & 0xFF) == EntryType.DIRECTORY;
			pos += 1;
			long size = (entries[pos] & 0xFFL) | ((entries[pos + 1] & 0xFFL) << 8) | ((entries[pos + 2] & 0xFFL) << 16)
					| ((entries[pos + 3] & 0xFFL) << 24);
			pos += 4;
			result.add(new FileEntry(name, directory, size));
		}
		return result;
	}

	public byte[] downloadFile(int volume, String path) throws IOException {
		Map<String, Object> decoded = sendAndDecode(CommandId.FILE_DOWNLOAD_REQUEST, CommandId.FILE_DATA,
				fields("VOLUME", (long) volume, "PATH", path));
		return (byte[]) decoded.get("DATA");
	}

	/**
	 * Timeout-overriding form of {@link #downloadFile} - see
	 * {@link #fileList(int, String, long)}.
	 */
	public byte[] downloadFile(int volume, String path, long timeoutMillis) throws IOException {
		Map<String, Object> decoded = sendAndDecode(CommandId.FILE_DOWNLOAD_REQUEST, CommandId.FILE_DATA,
				fields("VOLUME", (long) volume, "PATH", path), timeoutMillis);
		return (byte[]) decoded.get("DATA");
	}

	public void uploadFile(int volume, String path, byte[] data) throws IOException {
		send(CommandId.FILE_UPLOAD, fields("VOLUME", (long) volume, "PATH", path, "DATA", data));
	}

	/**
	 * Timeout-overriding form of {@link #uploadFile} - see
	 * {@link #fileList(int, String, long)}.
	 */
	public void uploadFile(int volume, String path, byte[] data, long timeoutMillis) throws IOException {
		send(CommandId.FILE_UPLOAD, fields("VOLUME", (long) volume, "PATH", path, "DATA", data), timeoutMillis);
	}

	public void deleteFile(int volume, String path) throws IOException {
		send(CommandId.FILE_DELETE, fields("VOLUME", (long) volume, "PATH", path));
	}

	/**
	 * Timeout-overriding form of {@link #deleteFile} - see
	 * {@link #fileList(int, String, long)}.
	 */
	public void deleteFile(int volume, String path, long timeoutMillis) throws IOException {
		send(CommandId.FILE_DELETE, fields("VOLUME", (long) volume, "PATH", path), timeoutMillis);
	}

	public Map<String, Object> storageInfo(int volume) throws IOException {
		return sendAndDecode(CommandId.STORAGE_INFO_REQUEST, CommandId.STORAGE_INFO_RESPONSE,
				fields("VOLUME", (long) volume));
	}

	public void copyFile(int srcVolume, String srcPath, int dstVolume, String dstPath) throws IOException {
		send(CommandId.FILE_COPY, fields("SRC_VOLUME", (long) srcVolume, "SRC_PATH", srcPath, "DST_VOLUME",
				(long) dstVolume, "DST_PATH", dstPath));
	}

	public void renameFile(int volume, String srcPath, String dstPath) throws IOException {
		send(CommandId.FILE_RENAME, fields("VOLUME", (long) volume, "SRC_PATH", srcPath, "DST_PATH", dstPath));
	}

	// --- GPIO (§15) ---

	public void gpioConfigure(int pin, int mode, int flags) throws IOException {
		send(CommandId.GPIO_CONFIGURE, fields("PIN_ID", (long) pin, "MODE", (long) mode, "FLAGS", (long) flags));
	}

	public void gpioWrite(int pin, boolean high) throws IOException {
		send(CommandId.GPIO_WRITE, fields("PIN_ID", (long) pin, "VALUE", bool(high)));
	}

	public Map<String, Object> gpioRead(int pin) throws IOException {
		return sendAndDecode(CommandId.GPIO_READ_REQUEST, CommandId.GPIO_READ_RESPONSE, fields("PIN_ID", (long) pin));
	}

	public void gpioPlayPattern(int pin, boolean initialLevelHigh, boolean repeatForever, int repeatCount,
			int... stepDurationsMs) throws IOException {
		long flags = (initialLevelHigh ? GpioPatternFlags.INITIAL_LEVEL_HIGH : 0)
				| (repeatForever ? GpioPatternFlags.REPEAT_FOREVER : 0);
		List<Long> steps = new ArrayList<>();
		for (int ms : stepDurationsMs) {
			steps.add((long) ms);
		}
		send(CommandId.GPIO_PLAY_PATTERN,
				fields("PIN_ID", (long) pin, "FLAGS", flags, "REPEAT_COUNT", (long) repeatCount, "STEPS", steps));
	}

	/**
	 * Convenience over {@link #gpioPlayPattern}, per doc/PROTOCOL.md §15.5's own
	 * suggestion.
	 */
	public void beep(int pin, int durationMs) throws IOException {
		gpioPlayPattern(pin, true, false, 0, durationMs);
	}

	public void doubleBeep(int pin, int durationMs, int gapMs) throws IOException {
		gpioPlayPattern(pin, true, false, 0, durationMs, gapMs, durationMs);
	}

	public void longFlash(int pin, int durationMs) throws IOException {
		gpioPlayPattern(pin, true, false, 0, durationMs);
	}

	// --- OTA (§16) ---

	public void otaInstall(byte[] imageData, int hashAlgo, byte[] hash, boolean applyNow) throws IOException {
		send(CommandId.OTA_INSTALL, fields("HASH_ALGO", (long) hashAlgo, "HASH", hash != null ? hash : new byte[0],
				"FLAGS", (long) (applyNow ? OtaInstallFlags.APPLY_NOW : 0), "IMAGE_DATA", imageData));
	}

	public void otaApply() throws IOException {
		send(CommandId.OTA_APPLY, fields());
	}

	public Map<String, Object> otaStatus() throws IOException {
		return sendAndDecode(CommandId.OTA_STATUS_REQUEST, CommandId.OTA_STATUS_RESPONSE, fields());
	}

	public void otaConfirm() throws IOException {
		send(CommandId.OTA_CONFIRM, fields());
	}

	public void otaRollback() throws IOException {
		send(CommandId.OTA_ROLLBACK, fields());
	}

	// --- power (§17) ---

	public void setPowerMode(int mode, int flags, long wakeAfterMs, int wakeButton) throws IOException {
		send(CommandId.SET_POWER_MODE, fields("MODE", (long) mode, "FLAGS", (long) flags, "WAKE_AFTER_MS", wakeAfterMs,
				"WAKE_BUTTON", (long) wakeButton));
	}

	public Map<String, Object> powerStatus() throws IOException {
		return sendAndDecode(CommandId.POWER_STATUS_REQUEST, CommandId.POWER_STATUS_RESPONSE, fields());
	}

	// --- macro recording/playback (§18) ---

	public void recordMacro() throws IOException {
		send(CommandId.RECORD_MACRO, fields());
	}

	public void saveMacro(int volume, String path) throws IOException {
		send(CommandId.SAVE_MACRO, fields("VOLUME", (long) volume, "PATH", path));
	}

	public void playMacro(int volume, String path) throws IOException {
		send(CommandId.PLAY_MACRO, fields("VOLUME", (long) volume, "PATH", path));
	}

	public void pause(long durationMs) throws IOException {
		send(CommandId.PAUSE, fields("DURATION_MS", durationMs));
	}

	// --- helpers ---

	private Frame send(int commandId, Map<String, Object> fieldValues) throws IOException {
		byte[] payload = PayloadCodec.encode(CommandSchema.byId(commandId), fieldValues);
		return commandClient.send(commandId, payload);
	}

	private Frame send(int commandId, Map<String, Object> fieldValues, long timeoutMillis) throws IOException {
		byte[] payload = PayloadCodec.encode(CommandSchema.byId(commandId), fieldValues);
		return commandClient.send(commandId, payload, timeoutMillis);
	}

	private Map<String, Object> sendAndDecode(int requestCommandId, int responseCommandId,
			Map<String, Object> fieldValues) throws IOException {
		Frame response = send(requestCommandId, fieldValues);
		return PayloadCodec.decode(CommandSchema.byId(responseCommandId), response.getPayload());
	}

	private Map<String, Object> sendAndDecode(int requestCommandId, int responseCommandId,
			Map<String, Object> fieldValues, long timeoutMillis) throws IOException {
		Frame response = send(requestCommandId, fieldValues, timeoutMillis);
		return PayloadCodec.decode(CommandSchema.byId(responseCommandId), response.getPayload());
	}

	private static long persistFlag(boolean persist) {
		return persist ? ConfigFlags.PERSIST : 0;
	}

	private static long bool(boolean b) {
		return b ? 1L : 0L;
	}

	private static Map<String, Object> fields(Object... nameValuePairs) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < nameValuePairs.length; i += 2) {
			m.put((String) nameValuePairs[i], nameValuePairs[i + 1]);
		}
		return m;
	}
}
