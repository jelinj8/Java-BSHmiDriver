package cz.bliksoft.hmieink.protocol.schema;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cz.bliksoft.hmieink.protocol.AuthLevel;
import cz.bliksoft.hmieink.protocol.ButtonEventType;
import cz.bliksoft.hmieink.protocol.ButtonId;
import cz.bliksoft.hmieink.protocol.ClearArtifactsFlags;
import cz.bliksoft.hmieink.protocol.Color;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.ConfigFlags;
import cz.bliksoft.hmieink.protocol.DrawMode;
import cz.bliksoft.hmieink.protocol.DrawTextFlags;
import cz.bliksoft.hmieink.protocol.Encoding;
import cz.bliksoft.hmieink.protocol.EntryType;
import cz.bliksoft.hmieink.protocol.FillTileMode;
import cz.bliksoft.hmieink.protocol.FontId;
import cz.bliksoft.hmieink.protocol.GpioConfigureFlags;
import cz.bliksoft.hmieink.protocol.GpioMode;
import cz.bliksoft.hmieink.protocol.GpioPatternFlags;
import cz.bliksoft.hmieink.protocol.ImageRowAlign;
import cz.bliksoft.hmieink.protocol.OrientationFlags;
import cz.bliksoft.hmieink.protocol.OtaHashAlgo;
import cz.bliksoft.hmieink.protocol.OtaInstallFlags;
import cz.bliksoft.hmieink.protocol.PowerMode;
import cz.bliksoft.hmieink.protocol.ReadScreenMode;
import cz.bliksoft.hmieink.protocol.ReadScreenSource;
import cz.bliksoft.hmieink.protocol.Rotation;
import cz.bliksoft.hmieink.protocol.ShiftDirection;
import cz.bliksoft.hmieink.protocol.Status;
import cz.bliksoft.hmieink.protocol.TextAlign;
import cz.bliksoft.hmieink.protocol.TextBackground;
import cz.bliksoft.hmieink.protocol.Volume;
import cz.bliksoft.hmieink.protocol.WakeReason;
import cz.bliksoft.hmieink.protocol.WifiConfigFlags;
import cz.bliksoft.hmieink.protocol.WriteFlags;

import static cz.bliksoft.hmieink.protocol.schema.FieldSpec.bytes;
import static cz.bliksoft.hmieink.protocol.schema.FieldSpec.bytesRef;
import static cz.bliksoft.hmieink.protocol.schema.FieldSpec.ipv4;
import static cz.bliksoft.hmieink.protocol.schema.FieldSpec.mac6;
import static cz.bliksoft.hmieink.protocol.schema.FieldSpec.repeatedStringTail;
import static cz.bliksoft.hmieink.protocol.schema.FieldSpec.repeatedU16leTail;
import static cz.bliksoft.hmieink.protocol.schema.FieldSpec.s16le;
import static cz.bliksoft.hmieink.protocol.schema.FieldSpec.string;
import static cz.bliksoft.hmieink.protocol.schema.FieldSpec.u16le;
import static cz.bliksoft.hmieink.protocol.schema.FieldSpec.u32le;
import static cz.bliksoft.hmieink.protocol.schema.FieldSpec.u8;
import static cz.bliksoft.hmieink.protocol.schema.FieldSpec.u8Enum;
import static cz.bliksoft.hmieink.protocol.schema.FieldSpec.u8Flags;

/**
 * The full command payload registry (doc/PROTOCOL.md §5-§18) - one {@link CommandSpec} per
 * {@link CommandId}, in wire field order. This is the single source of truth {@link PayloadCodec}
 * and {@code TextCommandFormat} both build on, so the wire layout only needs to be transcribed
 * from the spec once per command rather than duplicated across a typed API and a text grammar.
 *
 * <p>
 * Bulk-binary fields (image/screen/file/OTA payloads) are modeled as plain {@code BYTES} - RLE-
 * compressed image transfers are out of scope here (this schema always writes {@code
 * Encoding.RAW} on encode); use {@code EpiImageCodec}/the dedicated manual-check code for those.
 * {@code FILE_LIST_RESPONSE}'s repeated entries are similarly left as one opaque trailing blob
 * (decode-only, and this project already has no dedicated typed listing API to feed) rather than
 * fully itemized.
 */
public final class CommandSchema {

	private static final Map<Integer, CommandSpec> BY_ID = buildAll();
	private static final Map<String, CommandSpec> BY_NAME = buildByName();

	private CommandSchema() {
	}

	public static CommandSpec byId(int commandId) {
		CommandSpec spec = BY_ID.get(commandId);
		if (spec == null) {
			throw new IllegalArgumentException("no schema for commandId 0x" + Integer.toHexString(commandId));
		}
		return spec;
	}

	public static CommandSpec byName(String name) {
		CommandSpec spec = BY_NAME.get(name.toUpperCase(Locale.ROOT));
		if (spec == null) {
			throw new IllegalArgumentException("unknown command name: " + name);
		}
		return spec;
	}

	public static Collection<CommandSpec> all() {
		return BY_ID.values();
	}

	private static Map<String, CommandSpec> buildByName() {
		Map<String, CommandSpec> byName = new LinkedHashMap<>();
		for (CommandSpec spec : BY_ID.values()) {
			byName.put(spec.name, spec);
		}
		return Collections.unmodifiableMap(byName);
	}

	private static Map<Integer, CommandSpec> buildAll() {
		Map<Integer, CommandSpec> m = new LinkedHashMap<>();
		for (CommandSpec spec : new CommandSpec[] {
				// --- Core (0x0001-0x00FF), doc/PROTOCOL.md §5, §10, §10.1 ---
				spec(CommandId.HANDSHAKE_REQUEST, "HANDSHAKE_REQUEST", true, u8Enum("PIN_TYPE", AuthLevel.class),
						string("PIN", LengthPrefix.U8)),
				spec(CommandId.HANDSHAKE_RESPONSE, "HANDSHAKE_RESPONSE", false, bytes("TLV_DATA", LengthPrefix.NONE)),
				spec(CommandId.ACK, "ACK", false, u8("REF_SEQ"), u16le("REF_COMMAND_ID"), u8Enum("STATUS", Status.class)),
				spec(CommandId.NACK, "NACK", false, u8("REF_SEQ"), u16le("REF_COMMAND_ID"), u8Enum("STATUS", Status.class)),
				spec(CommandId.LOG_MESSAGE, "LOG_MESSAGE", true, bytes("MARKER", LengthPrefix.NONE)),

				// --- Image/display (0x0100-0x01FF), doc/PROTOCOL.md §6-§9 ---
				spec(CommandId.FULL_IMAGE_TRANSFER, "FULL_IMAGE_TRANSFER", true,
						u8Enum("ENCODING", Encoding.class).derived(f -> (long) Encoding.RAW),
						u8Flags("FLAGS", WriteFlags.class),
						u32le("DECODED_LEN").derived(f -> (long) ((byte[]) f.get("DATA")).length),
						u32le("ENCODED_LEN").derived(f -> (long) ((byte[]) f.get("DATA")).length),
						bytesRef("DATA", "ENCODED_LEN")),
				spec(CommandId.PARTIAL_IMAGE_TRANSFER, "PARTIAL_IMAGE_TRANSFER", true,
						u8Enum("ENCODING", Encoding.class).derived(f -> (long) Encoding.RAW),
						u8Flags("FLAGS", WriteFlags.class), u16le("X"), u16le("Y"), u16le("WIDTH"), u16le("HEIGHT"),
						u32le("DECODED_LEN").derived(f -> (long) ((byte[]) f.get("DATA")).length),
						u32le("ENCODED_LEN").derived(f -> (long) ((byte[]) f.get("DATA")).length),
						bytesRef("DATA", "ENCODED_LEN")),
				spec(CommandId.READ_SCREEN, "READ_SCREEN", true, u8Enum("SOURCE", ReadScreenSource.class),
						u8Enum("MODE", ReadScreenMode.class), u16le("X"), u16le("Y"), u16le("WIDTH"), u16le("HEIGHT")),
				spec(CommandId.SCREEN_DATA, "SCREEN_DATA", false, u8Enum("ENCODING", Encoding.class), u16le("X"),
						u16le("Y"), u16le("WIDTH"), u16le("HEIGHT"), u32le("DECODED_LEN"), u32le("ENCODED_LEN"),
						bytesRef("DATA", "ENCODED_LEN")),
				spec(CommandId.CLEAR_ARTIFACTS, "CLEAR_ARTIFACTS", true, u8("CYCLES"),
						u8Flags("FLAGS", ClearArtifactsFlags.class)),

				// --- Events (0x0200-0x02FF), doc/PROTOCOL.md §11 ---
				spec(CommandId.BUTTON_EVENT, "BUTTON_EVENT", false, u8Enum("BUTTON_ID", ButtonId.class),
						u8Enum("EVENT_TYPE", ButtonEventType.class), u32le("TIMESTAMP_MS")),

				// --- Drawing primitives (0x0300-0x03FF), doc/PROTOCOL.md §12 ---
				spec(CommandId.DRAW_LINE, "DRAW_LINE", true, u16le("X0"), u16le("Y0"), u16le("X1"), u16le("Y1"),
						u8Enum("COLOR", Color.class), u8Enum("DRAW_MODE", DrawMode.class), u8("LINE_WIDTH"),
						u8Flags("FLAGS", WriteFlags.class)),
				spec(CommandId.DRAW_RECT, "DRAW_RECT", true, u16le("X"), u16le("Y"), u16le("WIDTH"), u16le("HEIGHT"),
						u8Enum("COLOR", Color.class), u8Enum("DRAW_MODE", DrawMode.class), u8("FILLED"),
						u8("LINE_WIDTH"), u8Flags("FLAGS", WriteFlags.class)),
				spec(CommandId.DRAW_CIRCLE, "DRAW_CIRCLE", true, u16le("CENTER_X"), u16le("CENTER_Y"), u16le("RADIUS"),
						u8Enum("COLOR", Color.class), u8Enum("DRAW_MODE", DrawMode.class), u8("FILLED"),
						u8("LINE_WIDTH"), u8Flags("FLAGS", WriteFlags.class)),
				spec(CommandId.CLEAR_REGION, "CLEAR_REGION", true, u16le("X"), u16le("Y"), u16le("WIDTH"),
						u16le("HEIGHT"), u8Enum("COLOR", Color.class), u8Flags("FLAGS", WriteFlags.class)),
				spec(CommandId.DRAW_TEXT, "DRAW_TEXT", true, u16le("X"), u16le("Y"), u16le("WIDTH"),
						u8Enum("FONT_ID", FontId.class), u8Enum("COLOR", Color.class), u8Enum("BACKGROUND", TextBackground.class),
						u8Enum("DRAW_MODE", DrawMode.class), u8Enum("ALIGN", TextAlign.class), u8("WRAP"),
						u8Flags("FLAGS", WriteFlags.class, DrawTextFlags.class), string("TEXT", LengthPrefix.U16LE)),
				spec(CommandId.DRAW_IMAGE, "DRAW_IMAGE", true, u16le("X"), u16le("Y"),
						u8Enum("DRAW_MODE", DrawMode.class), u8Flags("FLAGS", WriteFlags.class),
						u8Enum("VOLUME", Volume.class), string("PATH", LengthPrefix.U8)),
				spec(CommandId.REFRESH, "REFRESH", true, u8("MODE")),
				spec(CommandId.SHIFT_REGION, "SHIFT_REGION", true, u16le("X"), u16le("Y"), u16le("WIDTH"),
						u16le("HEIGHT"), u8Enum("DIRECTION", ShiftDirection.class), u16le("STEP"),
						u8Enum("FILL_COLOR", Color.class), u8Flags("FLAGS", WriteFlags.class)),
				spec(CommandId.SET_CLIP_REGION, "SET_CLIP_REGION", true, u16le("X"), u16le("Y"), u16le("WIDTH"),
						u16le("HEIGHT")),
				spec(CommandId.COPY_REGION, "COPY_REGION", true, u16le("SRC_X"), u16le("SRC_Y"), u16le("DST_X"),
						u16le("DST_Y"), u16le("WIDTH"), u16le("HEIGHT"), u8Flags("FLAGS", WriteFlags.class)),
				spec(CommandId.SET_DRAW_OFFSET, "SET_DRAW_OFFSET", true, s16le("DX"), s16le("DY")),
				spec(CommandId.SET_ORIENTATION, "SET_ORIENTATION", true, u8Enum("ROTATION", Rotation.class),
						u8Flags("FLAGS", OrientationFlags.class)),
				spec(CommandId.DRAW_IMAGE_ROW, "DRAW_IMAGE_ROW", true, u16le("X"), u16le("Y"), u16le("WIDTH"),
						u8Enum("ALIGN", ImageRowAlign.class), u16le("SPACING"), u8Enum("DRAW_MODE", DrawMode.class),
						u8Flags("FLAGS", WriteFlags.class), u8Enum("VOLUME", Volume.class),
						u8("COUNT").derived(f -> (long) pathList(f).size()), repeatedStringTail("PATHS", "COUNT")),
				spec(CommandId.FILL_IMAGE, "FILL_IMAGE", true, u16le("X"), u16le("Y"), u16le("WIDTH"), u16le("HEIGHT"),
						u8Enum("TILE_MODE", FillTileMode.class), u8Enum("DRAW_MODE", DrawMode.class),
						u8Flags("FLAGS", WriteFlags.class), u8Enum("VOLUME", Volume.class),
						string("PATH", LengthPrefix.U8)),
				spec(CommandId.FAST_CLEAR, "FAST_CLEAR", true, u8Enum("COLOR", Color.class),
						u8Flags("FLAGS", WriteFlags.class)),
				spec(CommandId.SET_CUSTOM_FONT_FOLDER, "SET_CUSTOM_FONT_FOLDER", true, string("PATH", LengthPrefix.U8)),

				// --- Configuration (0x0400-0x04FF), doc/PROTOCOL.md §13 ---
				spec(CommandId.CONFIG_BACKUP_REQUEST, "CONFIG_BACKUP_REQUEST", true),
				spec(CommandId.CONFIG_BACKUP_DATA, "CONFIG_BACKUP_DATA", false, u8("CONFIG_VERSION"),
						bytes("TLV_DATA", LengthPrefix.NONE)),
				spec(CommandId.CONFIG_RESTORE, "CONFIG_RESTORE", true, u8("CONFIG_VERSION"),
						bytes("TLV_DATA", LengthPrefix.NONE)),
				spec(CommandId.SET_WIFI_CONFIG, "SET_WIFI_CONFIG", true, string("SSID", LengthPrefix.U8),
						string("PASSWORD", LengthPrefix.U8), u8Flags("FLAGS", ConfigFlags.class, WifiConfigFlags.class)),
				spec(CommandId.WIFI_STATUS_REQUEST, "WIFI_STATUS_REQUEST", true),
				spec(CommandId.WIFI_STATUS_RESPONSE, "WIFI_STATUS_RESPONSE", false, u8("ENABLED"), u8("CONNECTED"),
						string("SSID", LengthPrefix.U8), ipv4("IP_ADDRESS")),
				spec(CommandId.SET_WIFI_ENABLED, "SET_WIFI_ENABLED", true, u8("ENABLED"),
						u8Flags("FLAGS", ConfigFlags.class)),
				spec(CommandId.SET_BLE_ENABLED, "SET_BLE_ENABLED", true, u8("ENABLED"),
						u8Flags("FLAGS", ConfigFlags.class)),
				spec(CommandId.SET_BLE_PIN, "SET_BLE_PIN", true, u8("HAS_PIN"), u32le("PIN"),
						u8Flags("FLAGS", ConfigFlags.class)),
				spec(CommandId.BLE_STATUS_REQUEST, "BLE_STATUS_REQUEST", true),
				spec(CommandId.BLE_STATUS_RESPONSE, "BLE_STATUS_RESPONSE", false, u8("ENABLED"), u8("CONNECTED"),
						u8("HAS_PIN"), mac6("BLE_ADDRESS")),
				spec(CommandId.SET_DEVICE_NAME, "SET_DEVICE_NAME", true, string("NAME", LengthPrefix.U8),
						u8Flags("FLAGS", ConfigFlags.class)),
				spec(CommandId.SET_USAGE_PIN, "SET_USAGE_PIN", true, u8("HAS_PIN"), string("PIN", LengthPrefix.U8),
						u8Flags("FLAGS", ConfigFlags.class)),
				spec(CommandId.SET_ADMIN_PIN, "SET_ADMIN_PIN", true, u8("HAS_PIN"), string("PIN", LengthPrefix.U8),
						u8Flags("FLAGS", ConfigFlags.class)),

				// --- Storage (0x0600-0x06FF), doc/PROTOCOL.md §14 ---
				spec(CommandId.FILE_LIST_REQUEST, "FILE_LIST_REQUEST", true, u8Enum("VOLUME", Volume.class),
						string("PATH", LengthPrefix.U8)),
				spec(CommandId.FILE_LIST_RESPONSE, "FILE_LIST_RESPONSE", false, u16le("ENTRY_COUNT"),
						bytes("ENTRIES", LengthPrefix.NONE)),
				spec(CommandId.FILE_DOWNLOAD_REQUEST, "FILE_DOWNLOAD_REQUEST", true, u8Enum("VOLUME", Volume.class),
						string("PATH", LengthPrefix.U8)),
				spec(CommandId.FILE_DATA, "FILE_DATA", false,
						u32le("FILE_LEN").derived(f -> (long) ((byte[]) f.get("DATA")).length),
						bytesRef("DATA", "FILE_LEN")),
				spec(CommandId.FILE_UPLOAD, "FILE_UPLOAD", true, u8Enum("VOLUME", Volume.class),
						string("PATH", LengthPrefix.U8),
						u32le("FILE_LEN").derived(f -> (long) ((byte[]) f.get("DATA")).length),
						bytesRef("DATA", "FILE_LEN")),
				spec(CommandId.FILE_DELETE, "FILE_DELETE", true, u8Enum("VOLUME", Volume.class),
						string("PATH", LengthPrefix.U8)),
				spec(CommandId.STORAGE_INFO_REQUEST, "STORAGE_INFO_REQUEST", true, u8Enum("VOLUME", Volume.class)),
				spec(CommandId.STORAGE_INFO_RESPONSE, "STORAGE_INFO_RESPONSE", false, u8Enum("VOLUME", Volume.class),
						u8("PRESENT"), u32le("TOTAL_BYTES"), u32le("FREE_BYTES")),
				spec(CommandId.FILE_COPY, "FILE_COPY", true, u8Enum("SRC_VOLUME", Volume.class),
						string("SRC_PATH", LengthPrefix.U8), u8Enum("DST_VOLUME", Volume.class),
						string("DST_PATH", LengthPrefix.U8)),
				spec(CommandId.FILE_RENAME, "FILE_RENAME", true, u8Enum("VOLUME", Volume.class),
						string("SRC_PATH", LengthPrefix.U8), string("DST_PATH", LengthPrefix.U8)),

				// --- GPIO (0x0700-0x07FF), doc/PROTOCOL.md §15 ---
				spec(CommandId.GPIO_CONFIGURE, "GPIO_CONFIGURE", true, u8("PIN_ID"),
						u8Enum("MODE", GpioMode.class), u8Flags("FLAGS", GpioConfigureFlags.class)),
				spec(CommandId.GPIO_WRITE, "GPIO_WRITE", true, u8("PIN_ID"), u8("VALUE")),
				spec(CommandId.GPIO_READ_REQUEST, "GPIO_READ_REQUEST", true, u8("PIN_ID")),
				spec(CommandId.GPIO_READ_RESPONSE, "GPIO_READ_RESPONSE", false, u8("PIN_ID"), u8("VALUE"),
						u8Enum("MODE", GpioMode.class)),
				spec(CommandId.GPIO_EVENT, "GPIO_EVENT", false, u8("PIN_ID"), u8("VALUE"), u32le("TIMESTAMP_MS")),
				spec(CommandId.GPIO_PLAY_PATTERN, "GPIO_PLAY_PATTERN", true, u8("PIN_ID"),
						u8Flags("FLAGS", GpioPatternFlags.class),
						u8("STEP_COUNT").derived(f -> (long) stepList(f).size()), u8("REPEAT_COUNT"),
						repeatedU16leTail("STEPS", "STEP_COUNT")),

				// --- OTA (0x0800-0x08FF), doc/PROTOCOL.md §16 ---
				spec(CommandId.OTA_INSTALL, "OTA_INSTALL", true,
						u32le("TOTAL_LEN").derived(f -> (long) ((byte[]) f.get("IMAGE_DATA")).length),
						u8Enum("HASH_ALGO", OtaHashAlgo.class),
						u8("HASH_LEN").derived(f -> (long) OtaHashAlgo.hashLenFor(((Number) f.get("HASH_ALGO")).intValue())),
						bytesRef("HASH", "HASH_LEN"), u8Flags("FLAGS", OtaInstallFlags.class),
						bytesRef("IMAGE_DATA", "TOTAL_LEN")),
				spec(CommandId.OTA_APPLY, "OTA_APPLY", true),
				spec(CommandId.OTA_STATUS_REQUEST, "OTA_STATUS_REQUEST", true),
				spec(CommandId.OTA_STATUS_RESPONSE, "OTA_STATUS_RESPONSE", false, u8("RUNNING_SLOT"),
						u8("PENDING_VERIFICATION"), string("RUNNING_VERSION", LengthPrefix.U8)),
				spec(CommandId.OTA_CONFIRM, "OTA_CONFIRM", true),
				spec(CommandId.OTA_ROLLBACK, "OTA_ROLLBACK", true),

				// --- Power (0x0900-0x09FF), doc/PROTOCOL.md §17 ---
				spec(CommandId.SET_POWER_MODE, "SET_POWER_MODE", true, u8Enum("MODE", PowerMode.class), u8("FLAGS"),
						u32le("WAKE_AFTER_MS"), u8Enum("WAKE_BUTTON", ButtonId.class)),
				spec(CommandId.POWER_STATUS_REQUEST, "POWER_STATUS_REQUEST", true),
				spec(CommandId.POWER_STATUS_RESPONSE, "POWER_STATUS_RESPONSE", false,
						u8Enum("CURRENT_MODE", PowerMode.class), u8Enum("LAST_WAKE_REASON", WakeReason.class)),

				// --- Macro (0x0A00-0x0AFF), doc/PROTOCOL.md §18 ---
				spec(CommandId.RECORD_MACRO, "RECORD_MACRO", true),
				spec(CommandId.SAVE_MACRO, "SAVE_MACRO", true, u8Enum("VOLUME", Volume.class),
						string("PATH", LengthPrefix.U8)),
				spec(CommandId.PLAY_MACRO, "PLAY_MACRO", true, u8Enum("VOLUME", Volume.class),
						string("PATH", LengthPrefix.U8)),
				spec(CommandId.PAUSE, "PAUSE", true, u32le("DURATION_MS")), }) {
			m.put(spec.commandId, spec);
		}
		return Collections.unmodifiableMap(m);
	}

	@SuppressWarnings("unchecked")
	private static List<String> pathList(Map<String, Object> fields) {
		return (List<String>) fields.get("PATHS");
	}

	@SuppressWarnings("unchecked")
	private static List<Long> stepList(Map<String, Object> fields) {
		return (List<Long>) fields.get("STEPS");
	}

	private static CommandSpec spec(int commandId, String name, boolean sendable, FieldSpec... fields) {
		return new CommandSpec(commandId, name, sendable, fields);
	}
}
