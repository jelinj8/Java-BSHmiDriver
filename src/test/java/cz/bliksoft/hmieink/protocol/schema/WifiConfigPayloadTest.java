package cz.bliksoft.hmieink.protocol.schema;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.text.TextCommandFormat;

/**
 * SET_WIFI_CONFIG's IP/NETMASK/GATEWAY/DNS are on the wire only with
 * FLAGS.STATIC_IP (doc/PROTOCOL.md §13.2) - without it the payload stays the
 * original SSID+PASSWORD+FLAGS.
 */
class WifiConfigPayloadTest {

	@Test
	void dhcpPayloadEndsWithFlags() {
		TextCommandFormat.ParsedCommand dhcp = TextCommandFormat.parse("SET_WIFI_CONFIG|net|pw|PERSIST+CONNECT_NOW",
				'|');
		assertArrayEquals(new byte[] { 3, 'n', 'e', 't', 2, 'p', 'w', 0x03 }, dhcp.payload);
		assertThrows(IllegalArgumentException.class,
				() -> TextCommandFormat.parse("SET_WIFI_CONFIG|net|pw|PERSIST|192.168.20.94", '|'));
	}

	@Test
	void statusStaticIpIsOptionalForOlderFirmware() {
		byte[] older = { 1, 1, 3, 'n', 'e', 't', (byte) 192, (byte) 168, 20, 94 };
		byte[] current = Arrays.copyOf(older, older.length + 1);
		current[older.length] = 1;
		assertEquals("WIFI_STATUS_RESPONSE|1|1|net|192.168.20.94",
				TextCommandFormat.format(CommandId.WIFI_STATUS_RESPONSE, older, '|'));
		assertEquals("WIFI_STATUS_RESPONSE|1|1|net|192.168.20.94|1",
				TextCommandFormat.format(CommandId.WIFI_STATUS_RESPONSE, current, '|'));
		assertEquals(1L, PayloadCodec.decode(CommandSchema.byName("WIFI_STATUS_RESPONSE"), current).get("STATIC_IP"));
	}

	@Test
	void staticIpAppendsTheFourAddresses() {
		String line = "SET_WIFI_CONFIG|net|pw|PERSIST+CONNECT_NOW+STATIC_IP|192.168.20.94|255.255.255.0|192.168.20.1|0.0.0.0";
		TextCommandFormat.ParsedCommand cmd = TextCommandFormat.parse(line, '|');
		assertArrayEquals(new byte[] { 3, 'n', 'e', 't', 2, 'p', 'w', 0x07, (byte) 192, (byte) 168, 20, 94,
				(byte) 255, (byte) 255, (byte) 255, 0, (byte) 192, (byte) 168, 20, 1, 0, 0, 0, 0 }, cmd.payload);
		assertEquals(line, TextCommandFormat.format(CommandId.SET_WIFI_CONFIG, cmd.payload, '|'));
	}
}
