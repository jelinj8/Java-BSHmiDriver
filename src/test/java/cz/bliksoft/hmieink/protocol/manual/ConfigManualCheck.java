package cz.bliksoft.hmieink.protocol.manual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandNackException;
import cz.bliksoft.hmieink.protocol.ConfigFlags;
import cz.bliksoft.hmieink.protocol.Frame;
import cz.bliksoft.hmieink.protocol.HandshakeCapabilities;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;
import cz.bliksoft.hmieink.protocol.Tlv;
import cz.bliksoft.hmieink.protocol.TlvCodec;
import cz.bliksoft.hmieink.protocol.Volume;

/**
 * Manual, real-hardware verification of SET_DEVICE_NAME / CONFIG_BACKUP /
 * CONFIG_RESTORE (doc/PROTOCOL.md §13.1/§13.3) and the SD-over-NVS layered
 * config resolution (design note 72). Config-blob TYPE 0x01 is DEVICE_NAME,
 * firmware's own device-defined namespace (independent of the handshake's §5.2
 * TYPE namespace) - this tool decodes it with the same generic {@link TlvCodec}
 * used for the handshake, purely to assert against in tests; a real client is
 * meant to treat CONFIG_BACKUP_DATA as opaque. NOT part of the automated
 * {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.ConfigManualCheck COM5
 * </pre>
 */
public final class ConfigManualCheck {

	private static final int CONFIG_VERSION = 0x01;
	private static final int TLV_DEVICE_NAME = 0x01;
	private static final String SD_CONFIG_PATH = "/device.config";

	private static int failures = 0;

	private ConfigManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: ConfigManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		SerialFrameTransport transport = new SerialFrameTransport(portDescriptor);
		CommandClient client = new CommandClient(transport);
		System.out.println("Connecting to " + portDescriptor + "...");
		client.connect();
		try {
			String baselineName = currentDeviceName(client);
			System.out.println("baseline DEVICE_NAME=\"" + baselineName + "\" (expected MAC-derived default)");

			System.out.println("-> SET_DEVICE_NAME \"TestName-Session\" FLAGS=0 (session-only)");
			setDeviceName(client, "TestName-Session", 0);
			check("session-only name took effect immediately", "TestName-Session".equals(currentDeviceName(client)));
			check("session-only name did NOT get persisted (no DEVICE_NAME in CONFIG_BACKUP_DATA)",
					!backedUpDeviceName(client).isPresent());

			System.out.println("-> SET_DEVICE_NAME \"TestName-Persisted\" FLAGS=PERSIST");
			setDeviceName(client, "TestName-Persisted", ConfigFlags.PERSIST);
			check("persisted name took effect immediately", "TestName-Persisted".equals(currentDeviceName(client)));
			Optional<String> backedUp = backedUpDeviceName(client);
			check("persisted name appears in CONFIG_BACKUP_DATA",
					backedUp.isPresent() && backedUp.get().equals("TestName-Persisted"));
			byte[] savedBackupBlob = client.send(CommandId.CONFIG_BACKUP_REQUEST, new byte[0]).getPayload();

			System.out.println("-> SET_DEVICE_NAME \"\" (NAME_LEN=0) FLAGS=PERSIST - clear back to default");
			setDeviceName(client, "", ConfigFlags.PERSIST);
			check("clearing reverted to the MAC-derived baseline", baselineName.equals(currentDeviceName(client)));
			check("clearing with PERSIST erased the NVS entry too", !backedUpDeviceName(client).isPresent());

			System.out.println("-> CONFIG_RESTORE with the earlier saved backup blob");
			client.send(CommandId.CONFIG_RESTORE, savedBackupBlob);
			check("restored name took effect", "TestName-Persisted".equals(currentDeviceName(client)));
			check("restore re-persisted it too",
					backedUpDeviceName(client).map("TestName-Persisted"::equals).orElse(false));

			System.out.println("-> validation: SET_DEVICE_NAME with NAME_LEN=33 (over the 32 max) should NACK");
			expectNack(client, buildSetDeviceNamePayload(repeat('x', 33), 0));
			System.out.println("-> validation: CONFIG_RESTORE with a bad CONFIG_VERSION byte should NACK");
			expectNack(client, CommandId.CONFIG_RESTORE, new byte[] { (byte) 0xFF });

			boolean sdPresent = isSdPresent(client);
			System.out.println("SD card present: " + sdPresent);
			if (sdPresent) {
				runSdLayeringTest(client, transport, baselineName);
			} else {
				System.out.println("SKIPPING the SD-layering test - no SD card present (not a failure)");
			}

			System.out.println("-> cleanup: clearing the persisted name back to the MAC-derived default");
			setDeviceName(client, "", ConfigFlags.PERSIST);
			check("cleanup restored the baseline name", baselineName.equals(currentDeviceName(client)));

			System.out.println();
			if (failures == 0) {
				System.out.println("ALL CHECKS PASSED");
			} else {
				System.out.println("FAILURES: " + failures);
				System.exit(1);
			}
		} finally {
			client.close();
		}
	}

	/**
	 * Design note 72: NVS holds "SdLayerTest" persisted; the SD card's own
	 * /device.config, once present, should override it to "FROM-SD-CARD" after a
	 * reboot - and the NVS-persisted value underneath must stay untouched
	 * throughout, confirmed once the SD file is removed again.
	 */
	private static void runSdLayeringTest(CommandClient client, SerialFrameTransport transport, String baselineName)
			throws Exception {
		System.out.println("-> SD layering: SET_DEVICE_NAME \"SdLayerTest\" FLAGS=PERSIST (the NVS layer)");
		setDeviceName(client, "SdLayerTest", ConfigFlags.PERSIST);

		System.out.println("-> SD layering: uploading a /device.config blob naming \"FROM-SD-CARD\"");
		byte[] sdBlob = new TlvCodec.Builder().utf8(TLV_DEVICE_NAME, "FROM-SD-CARD").build();
		ByteBuffer full = ByteBuffer.allocate(1 + sdBlob.length);
		full.put((byte) CONFIG_VERSION);
		full.put(sdBlob);
		uploadToSd(client, SD_CONFIG_PATH, full.array());

		System.out.println("-> SD layering: resetToRunMode() to let setup()'s loadSdConfigLayer() pick it up");
		transport.resetToRunMode();
		check("SD layer overrides NVS after reboot", "FROM-SD-CARD".equals(currentDeviceName(client)));
		check("NVS-persisted value is untouched underneath the SD layer",
				backedUpDeviceName(client).map("SdLayerTest"::equals).orElse(false));

		System.out.println("-> SD layering: deleting " + SD_CONFIG_PATH + " and resetting again");
		client.send(CommandId.FILE_DELETE, buildVolumePathPayload(Volume.SD, SD_CONFIG_PATH));
		transport.resetToRunMode();
		check("falls back to the NVS layer once the SD file is gone", "SdLayerTest".equals(currentDeviceName(client)));
	}

	private static void uploadToSd(CommandClient client, String path, byte[] content) throws Exception {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length + 4 + content.length)
				.order(ByteOrder.LITTLE_ENDIAN);
		payload.put((byte) Volume.SD);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		payload.putInt(content.length);
		payload.put(content);
		client.send(CommandId.FILE_UPLOAD, payload.array());
	}

	private static byte[] buildVolumePathPayload(int volume, String path) {
		byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(2 + pathBytes.length);
		payload.put((byte) volume);
		payload.put((byte) pathBytes.length);
		payload.put(pathBytes);
		return payload.array();
	}

	private static boolean isSdPresent(CommandClient client) throws Exception {
		Frame response = client.send(CommandId.STORAGE_INFO_REQUEST, new byte[] { (byte) Volume.SD });
		return (response.getPayload()[1] & 0xFF) != 0;
	}

	private static String currentDeviceName(CommandClient client) throws Exception {
		// PIN_TYPE=NONE, PIN_LEN=0 (doc/PROTOCOL.md §5.3) - no pin offered.
		HandshakeCapabilities caps = HandshakeCapabilities
				.parse(client.send(CommandId.HANDSHAKE_REQUEST, new byte[] { 0, 0 }).getPayload());
		return caps.getDeviceName();
	}

	private static Optional<String> backedUpDeviceName(CommandClient client) throws Exception {
		byte[] payload = client.send(CommandId.CONFIG_BACKUP_REQUEST, new byte[0]).getPayload();
		if (payload.length == 0) {
			return Optional.empty();
		}
		byte[] tlvBytes = new byte[payload.length - 1];
		System.arraycopy(payload, 1, tlvBytes, 0, tlvBytes.length);
		List<Tlv> entries = TlvCodec.decode(tlvBytes);
		for (Tlv tlv : entries) {
			if (tlv.getType() == TLV_DEVICE_NAME) {
				return Optional.of(tlv.asUtf8());
			}
		}
		return Optional.empty();
	}

	private static void setDeviceName(CommandClient client, String name, int flags) throws Exception {
		client.send(CommandId.SET_DEVICE_NAME, buildSetDeviceNamePayload(name, flags));
	}

	private static byte[] buildSetDeviceNamePayload(String name, int flags) {
		byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
		ByteBuffer payload = ByteBuffer.allocate(1 + nameBytes.length + 1);
		payload.put((byte) nameBytes.length);
		payload.put(nameBytes);
		payload.put((byte) flags);
		return payload.array();
	}

	private static String repeat(char c, int count) {
		StringBuilder sb = new StringBuilder(count);
		for (int i = 0; i < count; i++) {
			sb.append(c);
		}
		return sb.toString();
	}

	private static void expectNack(CommandClient client, byte[] setDeviceNamePayload) throws Exception {
		expectNack(client, CommandId.SET_DEVICE_NAME, setDeviceNamePayload);
	}

	private static void expectNack(CommandClient client, int commandId, byte[] payload) throws Exception {
		try {
			client.send(commandId, payload);
			check("expected a NACK but got ACK/response", false);
		} catch (CommandNackException e) {
			check("got NACK(0x" + Integer.toHexString(e.getStatus()) + ")", true);
		}
	}

	private static void check(String description, boolean condition) {
		if (condition) {
			System.out.println("   OK: " + description);
		} else {
			System.out.println("   FAIL: " + description);
			failures++;
		}
	}
}
