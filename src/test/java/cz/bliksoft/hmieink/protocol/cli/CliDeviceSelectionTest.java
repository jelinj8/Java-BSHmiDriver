package cz.bliksoft.hmieink.protocol.cli;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import cz.bliksoft.javautils.ble.utils.BleUtils.BleDeviceResult;

import static org.junit.jupiter.api.Assertions.*;

public class CliDeviceSelectionTest {

    @Test
    public void testResolveDeviceStar() throws IOException {
        List<BleDeviceResult> devices = new ArrayList<>();
        devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "Device1", -50));

        // Should return first device
        BleDeviceResult result = Cli.resolveDevice(devices, "*");
        assertEquals("AA:BB:CC:DD:EE:FF", result.getAddress());
    }

    @Test
    public void testResolveDeviceStarEmpty() throws IOException {
        List<BleDeviceResult> devices = new ArrayList<>();

        // Should throw IOException for no devices
        assertThrows(IOException.class, () -> {
            Cli.resolveDevice(devices, "*");
        });
    }

    @Test
    public void testResolveDeviceOne() throws IOException {
        List<BleDeviceResult> devices = new ArrayList<>();
        devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "Device1", -50));

        // Should return the single device
        BleDeviceResult result = Cli.resolveDevice(devices, "1");
        assertEquals("AA:BB:CC:DD:EE:FF", result.getAddress());
    }

    @Test
    public void testResolveDeviceOneEmpty() throws IOException {
        List<BleDeviceResult> devices = new ArrayList<>();

        // Should throw IOException for no devices
        assertThrows(IOException.class, () -> {
            Cli.resolveDevice(devices, "1");
        });
    }

    @Test
    public void testResolveDeviceOneMultiple() throws IOException {
        List<BleDeviceResult> devices = new ArrayList<>();
        devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "Device1", -50));
        devices.add(new BleDeviceResult("11:22:33:44:55:66", "Device2", -60));

        // Should throw IOException for multiple devices
        assertThrows(IOException.class, () -> {
            Cli.resolveDevice(devices, "1");
        });
    }

    @Test
    public void testResolveDeviceNameMatch() throws IOException {
        List<BleDeviceResult> devices = new ArrayList<>();
        devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "MyDevice", -50));
        devices.add(new BleDeviceResult("11:22:33:44:55:66", "OtherDevice", -60));

        // Should match by name
        BleDeviceResult result = Cli.resolveDevice(devices, "my");
        assertEquals("AA:BB:CC:DD:EE:FF", result.getAddress());
    }

    @Test
    public void testResolveDeviceAddressMatch() throws IOException {
        List<BleDeviceResult> devices = new ArrayList<>();
        devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "MyDevice", -50));
        devices.add(new BleDeviceResult("11:22:33:44:55:66", "OtherDevice", -60));

        // Should match by address
        BleDeviceResult result = Cli.resolveDevice(devices, "AA:BB");
        assertEquals("AA:BB:CC:DD:EE:FF", result.getAddress());
    }

    @Test
    public void testResolveDeviceNoMatch() throws IOException {
        List<BleDeviceResult> devices = new ArrayList<>();
        devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "MyDevice", -50));

        // Should throw IOException for no match
        assertThrows(IOException.class, () -> {
            Cli.resolveDevice(devices, "nonexistent");
        });
    }

    @Test
    public void testResolveDeviceAmbiguous() throws IOException {
        List<BleDeviceResult> devices = new ArrayList<>();
        devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "MyDevice1", -50));
        devices.add(new BleDeviceResult("11:22:33:44:55:66", "MyDevice2", -60));

        // Should throw IOException for ambiguous match
        assertThrows(IOException.class, () -> {
            Cli.resolveDevice(devices, "my");
        });
    }
}
