package cz.bliksoft.hmieink.protocol.manual;

import cz.bliksoft.hmieink.protocol.ButtonEventType;
import cz.bliksoft.hmieink.protocol.ButtonId;
import cz.bliksoft.hmieink.protocol.CommandClient;
import cz.bliksoft.hmieink.protocol.CommandId;
import cz.bliksoft.hmieink.protocol.CommandTimeoutException;
import cz.bliksoft.hmieink.protocol.SerialFrameTransport;

/**
 * Manual, real-hardware verification of {@link SerialFrameTransport}'s new
 * RST/BOOT control methods (doc/PROTOCOL.md design note 71) - the CH340
 * auto-program circuit's DTR/RTS lines drive the same GPIO0 (BOOT)/EN (RST)
 * lines the board's own physical buttons pull. Checks three things a real board
 * is needed for: (1) pressBoot()/releaseBoot() while firmware is already
 * running produces a real BUTTON_EVENT for BUTTON_ID.BOOT, exactly like a
 * physical press; (2) resetToBootloader() leaves the device unresponsive to the
 * framed protocol (it's sitting in the ROM bootloader, not running firmware);
 * (3) resetToRunMode() recovers it back to normal, responsive operation. NOT
 * part of the automated {@code mvn test} suite - run it directly:
 *
 * <pre>
 * java -cp target/classes;target/test-classes;&lt;jserialcomm jar&gt; \
 *     cz.bliksoft.hmieink.protocol.manual.BoardControlManualCheck COM5
 * </pre>
 */
public final class BoardControlManualCheck {

	private static int failures = 0;

	private BoardControlManualCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: BoardControlManualCheck <port, e.g. COM5>");
			System.exit(2);
		}
		String portDescriptor = args[0];

		SerialFrameTransport transport = new SerialFrameTransport(portDescriptor);
		CommandClient client = new CommandClient(transport, 3000);
		boolean[] gotBootPress = { false };
		boolean[] gotBootRelease = { false };
		client.addEventListener(frame -> {
			if (frame.getCommandId() == CommandId.BUTTON_EVENT) {
				byte[] p = frame.getPayload();
				int buttonId = p[0] & 0xFF;
				int eventType = p[1] & 0xFF;
				System.out.println("   <- BUTTON_EVENT BUTTON_ID=0x" + Integer.toHexString(buttonId) + " EVENT_TYPE=0x"
						+ Integer.toHexString(eventType));
				if (buttonId == ButtonId.BOOT && eventType == ButtonEventType.PRESS) {
					gotBootPress[0] = true;
				}
				if (buttonId == ButtonId.BOOT && eventType == ButtonEventType.RELEASE) {
					gotBootRelease[0] = true;
				}
			}
		});

		System.out.println("Connecting to " + portDescriptor + "...");
		client.connect();
		try {
			System.out.println("-> HANDSHAKE_REQUEST (baseline - firmware should be running)");
			client.send(CommandId.HANDSHAKE_REQUEST, new byte[] { 0, 0 } /* PIN_TYPE=NONE, PIN_LEN=0 - §5.3 */);
			System.out.println("   OK: got a response - firmware is running");

			System.out.println("-> transport.pressBoot() - simulates holding the physical BOOT button");
			transport.pressBoot();
			Thread.sleep(200);
			System.out.println("-> transport.releaseBoot()");
			transport.releaseBoot();
			Thread.sleep(200);
			check("DTR-simulated BOOT press produced a real BUTTON_EVENT PRESS", gotBootPress[0]);
			check("DTR-simulated BOOT release produced a real BUTTON_EVENT RELEASE", gotBootRelease[0]);

			System.out.println("-> transport.resetToBootloader()");
			transport.resetToBootloader();
			System.out.println("-> HANDSHAKE_REQUEST (should now time out - device is in the ROM bootloader)");
			boolean timedOutAsExpected = false;
			try {
				client.send(CommandId.HANDSHAKE_REQUEST, new byte[] { 0, 0 } /* PIN_TYPE=NONE, PIN_LEN=0 - §5.3 */);
				System.out.println("   unexpectedly got a response");
			} catch (CommandTimeoutException e) {
				System.out.println("   OK: timed out as expected - device is unresponsive to the framed protocol");
				timedOutAsExpected = true;
			}
			check("device is unresponsive while in the ROM bootloader", timedOutAsExpected);

			System.out.println("-> transport.resetToRunMode() - recovering back to firmware");
			transport.resetToRunMode();
			System.out.println("-> HANDSHAKE_REQUEST (should succeed again - firmware is running again)");
			client.send(CommandId.HANDSHAKE_REQUEST, new byte[] { 0, 0 } /* PIN_TYPE=NONE, PIN_LEN=0 - §5.3 */);
			System.out.println("   OK: got a response - firmware recovered and is running normally");

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

	private static void check(String description, boolean condition) {
		if (condition) {
			System.out.println("   OK: " + description);
		} else {
			System.out.println("   FAIL: " + description);
			failures++;
		}
	}
}
