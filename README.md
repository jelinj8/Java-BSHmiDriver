# BSHmiDriver

Generic Java PC-side client for a wire protocol that remote-controls small e-paper/LCD HMI
displays — image transfer, local drawing primitives, storage, GPIO, OTA, power management — over
WiFi/TCP, Bluetooth Low Energy, or Serial. Not tied to any specific device.

## Why a generic protocol client

Most small HMI display projects hard-code their PC-side communication layer to one specific board.
This library instead implements a fully specified, versionable wire protocol (frame envelope, TLV
capability negotiation, a ~50-command catalog covering image transfer, drawing primitives, storage,
GPIO, OTA, and power management), so any device that speaks it — today, the CrowPanel 4.2" e-paper
display via the sibling `firmware-BSHMIEinkDevice` firmware — can be driven from the same Java
client, over whichever transport is available (Serial, TCP, or BLE), through the same typed API.

The protocol spec itself (`doc/PROTOCOL.md`) lives in the `firmware-BSHMIEinkDevice` repository —
the main published asset it belongs alongside — not here. This library and the firmware are
independent implementations of that one spec, kept in lockstep by convention.

## Transports

- `SerialFrameTransport` — UART, via `jSerialComm` (provided dependency).
- `TcpFrameTransport` — WiFi/TCP, raw sockets.
- `BleFrameTransport` — Bluetooth Low Energy, built on the sibling `BSToolbox-BLE` library
  (provided dependency, not required unless you use BLE).
- `FileFrameTransport` — writes commands to a `.macro` file instead of a live device; used by
  `Cli`'s file-output mode and for hand-authoring macros.

## Modules

`cz.bliksoft.hmieink.protocol` holds only wire-protocol definitions; everything built on top of it —
the CLI, the integration entry point, and PC-side tooling — lives in top-level siblings.

- **`cz.bliksoft.hmieink.protocol`** — the wire format itself: frame envelope (`Frame`, `Crc16`,
  `RlePackBits`), `CommandClient` (SEQ assignment, ACK/NACK-vs-direct-response correlation,
  timeout+retry, an event-listener hook for unsolicited `BUTTON_EVENT`/`GPIO_EVENT`/`LOG_MESSAGE`
  frames), and `HmiDevice` with its transport-specific subclasses (`SerialHmiDevice`/`TcpHmiDevice`/
  `BleHmiDevice`/`FileHmiDevice`) — a high-level, typed API (one method per command), never
  referencing a transport's own dependency directly so a consumer only pulls in what it uses.
- **`cz.bliksoft.hmieink.protocol.schema`** — `CommandSchema`, a declarative, single-source-of-truth
  field layout for every command (covering the full ~50-command catalog), driving `PayloadCodec`
  (byte[] ↔ field map) and `TextCommandFormat` (bidirectional textual notation), so neither
  duplicates the wire layout by hand.
- **`cz.bliksoft.hmieink`** — `Cli`, a command-line front end: `-t`/`-a` transport selection,
  `-f`/`-c`/`-p` (file/inline/piped commands, order-preserving), a plaintext command notation
  (`NAME|field|field|...`, escaping, `@file` for raw-byte fields), and PC-local pseudo-commands
  (`SLEEP`, `WAIT_LOG`, `SYNC`, `ICONSPEC`) via `ScriptRunner`. Also `HmiUtils`, the integration
  entry point (see below).
- **`cz.bliksoft.hmieink.text`**, **`.script`**, **`.sync`**, **`.font`**, **`.image`**,
  **`.macro`** — PC-side tooling built on top of the protocol (text notation, script execution,
  folder sync, font rasterization, `.epi`/`.macro` file codecs) — see CLAUDE.md's package-layout
  section for the full breakdown.

## Usage

See `doc/cli.md` for comprehensive CLI documentation including:
- Connection specification (TCP, Serial, BLE, File)
- Command format and escaping rules
- PC-local pseudo-commands (`SLEEP`, `WAIT_LOG`, `SYNC`, `ICONSPEC`)
- Full command catalog and examples

### Java API

```java
try (SerialHmiDevice device = HmiUtils.Serial.connectAndHandshake("COM5", null, null)) {
    device.fastClear(Color.WHITE, 0);
    device.drawRect(10, 10, 100, 60, Color.BLACK, DrawMode.REPLACE, true, 2, WriteFlags.REFRESH_NOW);
}
```

`HmiUtils` (`cz.bliksoft.hmieink.HmiUtils`) is the integration entry point for connecting and
handshaking without hand-rolling that sequence yourself — one nested class per transport
(`HmiUtils.Serial`/`.Tcp`/`.File`/`.Ble`), so using only one transport never pulls the others'
`provided` dependencies onto your classpath.

### CLI

The CLI requires transport-specific dependencies on the classpath. For Serial or BLE, include `jSerialComm` or `BSToolbox-BLE` respectively. The easiest way to run is using the distributed package built with `mvn package -Pdist`:

```bash
# Build distribution
mvn package -Pdist

# Then use the launch scripts (automatically includes all dependencies)
./hmi-cli.sh -t serial -a COM5 -c "FAST_CLEAR|WHITE|0"
./hmi-cli.sh -t ble -a "*" -c "FAST_CLEAR|WHITE|0"
./hmi-cli.sh -t tcp -a "192.168.1.100:8080" -c "FAST_CLEAR|WHITE|0"
```

Or manually with java (for TCP only, or when you supply dependencies yourself):

```bash
# TCP only (no external dependencies required)
java -jar bshmidriver-cli.jar -t tcp -a "192.168.1.100:8080" -c "FAST_CLEAR|WHITE|0"
```

See `doc/cli.md` for complete CLI documentation.

## Building

```bash
mvn test
```

`jSerialComm`, `common-java-utils-ble` (BSToolbox-BLE), `picocli`, and `common-java-utils`
(BSToolbox, needed for `Cli`'s `ICONSPEC` command and the `GenerateDeviceFonts` manual tool) are
all `provided` — only pull in the one(s) you actually use, on your own consuming application's
classpath. `common-java-utils-ble` is on Maven Central as of 0.3.0; `common-java-utils` as of 0.8.

## Release Packaging

To build a standalone CLI distribution with all required dependencies included:

```bash
mvn package -Pdist
```

This creates:
- `target/bshmidriver-<version>/` — directory with the packaged CLI (jar, lib folder, and launch scripts)
- `target/bshmidriver-<version>.zip` — zipped version of the distribution

The distribution includes:
- `bshmidriver-cli.jar` — the main application jar
- `lib/` — all required dependencies (jSerialComm, BSToolbox-BLE, picocli)
- Launch scripts for Windows (`hmi-cli.bat`), Linux (`hmi-cli.sh`) and macOS (`hmi-cli.command`)

## Status

Verified end-to-end on real hardware against the CrowPanel 4.2" e-paper display: all three
transports (Serial, WiFi/TCP, BLE) live and independently functional simultaneously on one firmware
image; the full drawing/storage/GPIO/OTA/power-management/macro command surface exercised via
dozens of manual real-hardware check tools; the high-level `HmiDevice`/`Cli` layer confirmed against
real device responses over Serial. See the `firmware-BSHMIEinkDevice` repository's
`doc/PROTOCOL.md` §22 for the complete, per-feature verification history.

## License

LGPL-2.1-or-later, see `LICENSE`.
