package cz.bliksoft.hmieink.protocol;

/**
 * Command IDs (doc/PROTOCOL.md §4). Mirrors firmware's {@code Protocol.h}
 * {@code cmd} namespace - keep both in sync.
 */
public final class CommandId {

	private CommandId() {
	}

	public static final int HANDSHAKE_REQUEST = 0x0001;
	public static final int HANDSHAKE_RESPONSE = 0x0002;
	public static final int ACK = 0x0003;
	public static final int NACK = 0x0004;
	public static final int LOG_MESSAGE = 0x0005;

	public static final int FULL_IMAGE_TRANSFER = 0x0100;
	public static final int PARTIAL_IMAGE_TRANSFER = 0x0101;
	public static final int READ_SCREEN = 0x0102;
	public static final int SCREEN_DATA = 0x0103;
	public static final int CLEAR_ARTIFACTS = 0x0104;

	public static final int BUTTON_EVENT = 0x0200;

	public static final int DRAW_LINE = 0x0300;
	public static final int DRAW_RECT = 0x0301;
	public static final int DRAW_CIRCLE = 0x0302;
	public static final int CLEAR_REGION = 0x0303;
	public static final int DRAW_TEXT = 0x0304;
	public static final int DRAW_IMAGE = 0x0305;
	public static final int REFRESH = 0x0306;
	public static final int SHIFT_REGION = 0x0307;
	public static final int SET_CLIP_REGION = 0x0308;
	public static final int COPY_REGION = 0x0309;
	public static final int SET_DRAW_OFFSET = 0x030A;
	public static final int SET_ORIENTATION = 0x030B;
	public static final int DRAW_IMAGE_ROW = 0x030C;
	public static final int FILL_IMAGE = 0x030D;
	public static final int FAST_CLEAR = 0x030E;
	public static final int SET_CUSTOM_FONT_FOLDER = 0x030F;

	public static final int CONFIG_BACKUP_REQUEST = 0x0400;
	public static final int CONFIG_BACKUP_DATA = 0x0401;
	public static final int CONFIG_RESTORE = 0x0402;
	public static final int SET_WIFI_CONFIG = 0x0403;
	public static final int WIFI_STATUS_REQUEST = 0x0404;
	public static final int WIFI_STATUS_RESPONSE = 0x0405;
	public static final int SET_WIFI_ENABLED = 0x0406;
	public static final int SET_BLE_ENABLED = 0x0407;
	public static final int SET_BLE_PIN = 0x0408;
	public static final int BLE_STATUS_REQUEST = 0x0409;
	public static final int BLE_STATUS_RESPONSE = 0x040A;
	public static final int SET_DEVICE_NAME = 0x040B;
	public static final int SET_USAGE_PIN = 0x040C;
	public static final int SET_ADMIN_PIN = 0x040D;

	public static final int FILE_LIST_REQUEST = 0x0600;
	public static final int FILE_LIST_RESPONSE = 0x0601;
	public static final int FILE_DOWNLOAD_REQUEST = 0x0602;
	public static final int FILE_DATA = 0x0603;
	public static final int FILE_UPLOAD = 0x0604;
	public static final int FILE_DELETE = 0x0605;
	public static final int STORAGE_INFO_REQUEST = 0x0606;
	public static final int STORAGE_INFO_RESPONSE = 0x0607;
	public static final int FILE_COPY = 0x0608;
	public static final int FILE_RENAME = 0x0609;

	public static final int GPIO_CONFIGURE = 0x0700;
	public static final int GPIO_WRITE = 0x0701;
	public static final int GPIO_READ_REQUEST = 0x0702;
	public static final int GPIO_READ_RESPONSE = 0x0703;
	public static final int GPIO_EVENT = 0x0704;
	public static final int GPIO_PLAY_PATTERN = 0x0705;

	public static final int OTA_INSTALL = 0x0800;
	public static final int OTA_APPLY = 0x0801;
	public static final int OTA_STATUS_REQUEST = 0x0802;
	public static final int OTA_STATUS_RESPONSE = 0x0803;
	public static final int OTA_CONFIRM = 0x0804;
	public static final int OTA_ROLLBACK = 0x0805;

	public static final int SET_POWER_MODE = 0x0900;
	public static final int POWER_STATUS_REQUEST = 0x0901;
	public static final int POWER_STATUS_RESPONSE = 0x0902;

	public static final int RECORD_MACRO = 0x0A00;
	public static final int SAVE_MACRO = 0x0A01;
	public static final int PLAY_MACRO = 0x0A02;
	public static final int PAUSE = 0x0A03;
}
