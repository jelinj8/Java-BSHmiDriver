package cz.bliksoft.hmieink.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Spot-checks a few constants against doc/PROTOCOL.md so a future edit that touches one side
 * (Java or firmware) but not the other, or not the doc, is more likely to be noticed.
 */
class CommandIdTest {

	@Test
	void otaCommandsAreInTheDocumentedRange() {
		assertEquals(0x0800, CommandId.OTA_INSTALL);
		assertEquals(0x0805, CommandId.OTA_ROLLBACK);
	}

	@Test
	void everyCommandIdFitsInAU16() {
		assertTrue(CommandId.OTA_ROLLBACK <= 0xFFFF);
	}

	@Test
	void newOtaStatusCodesDontCollideWithExistingOnes() {
		assertEquals(0x0C, Status.OTA_HASH_MISMATCH);
		assertEquals(0x0D, Status.OTA_NOT_STAGED);
	}

	@Test
	void networkConfigCommandsAreInTheDocumentedRange() {
		assertEquals(0x0403, CommandId.SET_WIFI_CONFIG);
		assertEquals(0x040A, CommandId.BLE_STATUS_RESPONSE);
	}

	@Test
	void powerManagementCommandsAreInTheDocumentedRange() {
		assertEquals(0x0900, CommandId.SET_POWER_MODE);
		assertEquals(0x0902, CommandId.POWER_STATUS_RESPONSE);
	}

	@Test
	void powerModeAndWakeReasonConstantsDontCollide() {
		assertEquals(0x02, PowerMode.HARD_SLEEP);
		assertEquals(0x06, WakeReason.LOW_POWER_BLE_ACTIVITY);
	}

	@Test
	void gpioPlayPatternIsInTheDocumentedRange() {
		assertEquals(0x0705, CommandId.GPIO_PLAY_PATTERN);
		assertEquals(0x02, GpioPatternFlags.REPEAT_FOREVER);
	}

	@Test
	void drawingPrimitiveCommandsAreInTheDocumentedRange() {
		assertEquals(0x0300, CommandId.DRAW_LINE);
		assertEquals(0x0301, CommandId.DRAW_RECT);
		assertEquals(0x0302, CommandId.DRAW_CIRCLE);
		assertEquals(0x0303, CommandId.CLEAR_REGION);
		assertEquals(0x03, DrawMode.AND);
		assertEquals(0x01, Color.BLACK);
	}

	@Test
	void shiftRegionIsInTheDocumentedRange() {
		assertEquals(0x0307, CommandId.SHIFT_REGION);
		assertEquals(0x03, ShiftDirection.DOWN);
	}

	@Test
	void clipAndCopyRegionAreInTheDocumentedRange() {
		assertEquals(0x0308, CommandId.SET_CLIP_REGION);
		assertEquals(0x0309, CommandId.COPY_REGION);
		assertEquals(0x02, TextAlign.RIGHT);
	}

	@Test
	void textBackgroundConstantsAreInTheDocumentedRange() {
		assertEquals(0x00, TextBackground.TRANSPARENT);
		assertEquals(0x01, TextBackground.OPAQUE);
	}

	@Test
	void drawOffsetAndOrientationAreInTheDocumentedRange() {
		assertEquals(0x030A, CommandId.SET_DRAW_OFFSET);
		assertEquals(0x030B, CommandId.SET_ORIENTATION);
		assertEquals(0x03, Rotation.ROTATE_270);
		assertEquals(0x02, OrientationFlags.MIRROR_V);
	}

	@Test
	void readScreenAndClearArtifactsConstantsAreInTheDocumentedRange() {
		assertEquals(0x0102, CommandId.READ_SCREEN);
		assertEquals(0x0103, CommandId.SCREEN_DATA);
		assertEquals(0x0104, CommandId.CLEAR_ARTIFACTS);
		assertEquals(0x01, ReadScreenSource.WORKING_BUFFER);
		assertEquals(0x01, ReadScreenMode.REGION);
		assertEquals(0x01, ClearArtifactsFlags.RESTORE_CONTENT);
	}

	@Test
	void storageCommandsAndConstantsAreInTheDocumentedRange() {
		assertEquals(0x0600, CommandId.FILE_LIST_REQUEST);
		assertEquals(0x0601, CommandId.FILE_LIST_RESPONSE);
		assertEquals(0x0602, CommandId.FILE_DOWNLOAD_REQUEST);
		assertEquals(0x0603, CommandId.FILE_DATA);
		assertEquals(0x0604, CommandId.FILE_UPLOAD);
		assertEquals(0x0605, CommandId.FILE_DELETE);
		assertEquals(0x0606, CommandId.STORAGE_INFO_REQUEST);
		assertEquals(0x0607, CommandId.STORAGE_INFO_RESPONSE);
		assertEquals(0x0608, CommandId.FILE_COPY);
		assertEquals(0x0609, CommandId.FILE_RENAME);
		assertEquals(0x01, Volume.INTERNAL);
		assertEquals(0x01, EntryType.DIRECTORY);
	}

	@Test
	void drawImageRowIsInTheDocumentedRange() {
		assertEquals(0x030C, CommandId.DRAW_IMAGE_ROW);
		assertEquals(0x03, ImageRowAlign.BLOCK);
	}

	@Test
	void macroCommandsAndPsramVolumeAreInTheDocumentedRange() {
		assertEquals(0x0A00, CommandId.RECORD_MACRO);
		assertEquals(0x0A01, CommandId.SAVE_MACRO);
		assertEquals(0x0A02, CommandId.PLAY_MACRO);
		assertEquals(0x0A03, CommandId.PAUSE);
		assertEquals(0x02, Volume.PSRAM);
	}

	@Test
	void fillImageIsInTheDocumentedRange() {
		assertEquals(0x030D, CommandId.FILL_IMAGE);
		assertEquals(0x02, FillTileMode.BOTH);
	}

	@Test
	void drawTextFlagsTextIsPathIsInTheDocumentedRange() {
		assertEquals(0x04, DrawTextFlags.TEXT_IS_PATH);
	}
}
