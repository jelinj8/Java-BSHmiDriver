package cz.bliksoft.hmieink.protocol.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import cz.bliksoft.javautils.ble.utils.BleUtils.BleDeviceResult;

import static org.junit.jupiter.api.Assertions.*;

public class CliPrintScanResultsTest {

	@Test
	public void testPrintScanResultsEmpty() throws IOException {
		List<BleDeviceResult> devices = new ArrayList<>();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream printStream = new PrintStream(out, true);
		PrintStream originalOut = System.out;
		System.setOut(printStream);

		try {
			Cli.printScanResults(devices);
		} finally {
			System.setOut(originalOut);
			printStream.close();
		}

		String output = out.toString();
		// On Windows, printf uses \r\n, on Unix-like systems it uses \n
		String normalized = output.replace("\r\n", "\n");
		assertEquals("No devices found.\n", normalized);
	}

	@Test
	public void testPrintScanResultsSingleDevice() throws IOException {
		List<BleDeviceResult> devices = new ArrayList<>();
		devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "Device1", -50));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream printStream = new PrintStream(out, true);
		PrintStream originalOut = System.out;
		System.setOut(printStream);

		try {
			Cli.printScanResults(devices);
		} finally {
			System.setOut(originalOut);
			printStream.close();
		}

		String output = out.toString();
		assertTrue(output.contains("AA:BB:CC:DD:EE:FF"));
		assertTrue(output.contains("Device1"));
	}

	@Test
	public void testPrintScanResultsMultipleDevices() throws IOException {
		List<BleDeviceResult> devices = new ArrayList<>();
		devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "Device1", -50));
		devices.add(new BleDeviceResult("11:22:33:44:55:66", "Device2", -60));
		devices.add(new BleDeviceResult("DE:AD:BE:EF:CA:FE", null, -70));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream printStream = new PrintStream(out, true);
		PrintStream originalOut = System.out;
		System.setOut(printStream);

		try {
			Cli.printScanResults(devices);
		} finally {
			System.setOut(originalOut);
			printStream.close();
		}

		String output = out.toString();
		assertTrue(output.contains("AA:BB:CC:DD:EE:FF"));
		assertTrue(output.contains("Device1"));
		assertTrue(output.contains("11:22:33:44:55:66"));
		assertTrue(output.contains("Device2"));
		assertTrue(output.contains("DE:AD:BE:EF:CA:FE"));
		assertTrue(output.contains("(no name)"));
	}

	@Test
	public void testPrintScanResultsHeaderFormat() throws IOException {
		List<BleDeviceResult> devices = new ArrayList<>();
		devices.add(new BleDeviceResult("AA:BB:CC:DD:EE:FF", "Device1", -50));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream printStream = new PrintStream(out, true);
		PrintStream originalOut = System.out;
		System.setOut(printStream);

		try {
			Cli.printScanResults(devices);
		} finally {
			System.setOut(originalOut);
			printStream.close();
		}

		String output = out.toString();
		assertTrue(output.contains("Address"));
		assertTrue(output.contains("Name"));
	}
}
