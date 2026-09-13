package cz.bliksoft.hmieink;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import cz.bliksoft.javautils.ble.utils.BleUtils.BleDeviceResult;

import static org.junit.jupiter.api.Assertions.*;

public class HmiUtilsBleDeviceSelectionTest {

	private static final long SCAN_TIMEOUT_MS = 1000;

	@Test
	public void testResolveDeviceStar() throws IOException {
		List<BleDeviceResult> devices = new ArrayList<>();
		devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "Device1", -50));

		// Should return first device
		BleDeviceResult result = HmiUtils.Ble.resolveDevice(devices, "*", SCAN_TIMEOUT_MS);
		assertEquals("AA:BB:CC:DD:EE:FF", result.getAddress());
	}

	@Test
	public void testResolveDeviceStarEmpty() {
		List<BleDeviceResult> devices = new ArrayList<>();

		// Should throw IOException for no devices
		assertThrows(IOException.class, () -> {
			HmiUtils.Ble.resolveDevice(devices, "*", SCAN_TIMEOUT_MS);
		});
	}

	@Test
	public void testResolveDeviceOne() throws IOException {
		List<BleDeviceResult> devices = new ArrayList<>();
		devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "Device1", -50));

		// Should return the single device
		BleDeviceResult result = HmiUtils.Ble.resolveDevice(devices, "1", SCAN_TIMEOUT_MS);
		assertEquals("AA:BB:CC:DD:EE:FF", result.getAddress());
	}

	@Test
	public void testResolveDeviceOneEmpty() {
		List<BleDeviceResult> devices = new ArrayList<>();

		// Should throw IOException for no devices
		assertThrows(IOException.class, () -> {
			HmiUtils.Ble.resolveDevice(devices, "1", SCAN_TIMEOUT_MS);
		});
	}

	@Test
	public void testResolveDeviceOneMultiple() {
		List<BleDeviceResult> devices = new ArrayList<>();
		devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "Device1", -50));
		devices.add(new BleDeviceResult("11:22:33:44:55:66", "Device2", -60));

		// Should throw IOException for multiple devices
		assertThrows(IOException.class, () -> {
			HmiUtils.Ble.resolveDevice(devices, "1", SCAN_TIMEOUT_MS);
		});
	}

	@Test
	public void testResolveDeviceNameMatch() throws IOException {
		List<BleDeviceResult> devices = new ArrayList<>();
		devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "MyDevice", -50));
		devices.add(new BleDeviceResult("11:22:33:44:55:66", "OtherDevice", -60));

		// Should match by name
		BleDeviceResult result = HmiUtils.Ble.resolveDevice(devices, "my", SCAN_TIMEOUT_MS);
		assertEquals("AA:BB:CC:DD:EE:FF", result.getAddress());
	}

	@Test
	public void testResolveDeviceAddressMatch() throws IOException {
		List<BleDeviceResult> devices = new ArrayList<>();
		devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "MyDevice", -50));
		devices.add(new BleDeviceResult("11:22:33:44:55:66", "OtherDevice", -60));

		// Should match by address
		BleDeviceResult result = HmiUtils.Ble.resolveDevice(devices, "AA:BB", SCAN_TIMEOUT_MS);
		assertEquals("AA:BB:CC:DD:EE:FF", result.getAddress());
	}

	@Test
	public void testResolveDeviceNoMatch() {
		List<BleDeviceResult> devices = new ArrayList<>();
		devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "MyDevice", -50));

		// Should throw IOException for no match
		assertThrows(IOException.class, () -> {
			HmiUtils.Ble.resolveDevice(devices, "nonexistent", SCAN_TIMEOUT_MS);
		});
	}

	@Test
	public void testResolveDeviceAmbiguous() {
		List<BleDeviceResult> devices = new ArrayList<>();
		devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "MyDevice1", -50));
		devices.add(new BleDeviceResult("11:22:33:44:55:66", "MyDevice2", -60));

		// Should throw IOException for ambiguous match
		assertThrows(IOException.class, () -> {
			HmiUtils.Ble.resolveDevice(devices, "my", SCAN_TIMEOUT_MS);
		});
	}

	@Test
	public void testResolveExactAddressMatch() throws IOException {
		List<BleDeviceResult> devices = new ArrayList<>();
		devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "MyDevice", -50));
		devices.add(new BleDeviceResult("11:22:33:44:55:66", "OtherDevice", -60));

		List<BleDeviceResult> result = HmiUtils.Ble.resolveExact(devices, "AA:BB:CC:DD:EE:FF", SCAN_TIMEOUT_MS);
		assertEquals(1, result.size());
		assertEquals("AA:BB:CC:DD:EE:FF", result.get(0).getAddress());
	}

	@Test
	public void testResolveExactNameMatchMultiple() throws IOException {
		List<BleDeviceResult> devices = new ArrayList<>();
		devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "SharedName", -50));
		devices.add(new BleDeviceResult("11:22:33:44:55:66", "SharedName", -60));
		devices.add(new BleDeviceResult("22:33:44:55:66:77", "OtherDevice", -70));

		// Unlike resolveDevice, resolveExact can legitimately return more than one
		// match
		List<BleDeviceResult> result = HmiUtils.Ble.resolveExact(devices, "SharedName", SCAN_TIMEOUT_MS);
		assertEquals(2, result.size());
	}

	@Test
	public void testResolveExactNoMatch() {
		List<BleDeviceResult> devices = new ArrayList<>();
		devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "MyDevice", -50));

		assertThrows(IOException.class, () -> {
			HmiUtils.Ble.resolveExact(devices, "nonexistent", SCAN_TIMEOUT_MS);
		});
	}
}
