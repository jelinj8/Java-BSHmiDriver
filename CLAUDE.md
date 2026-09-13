# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`cz.bliksoft.hmieink:bshmidriver` (`BSHmiDriver`) — a generic Java PC-side client for a wire
protocol that remote-controls small e-paper/LCD HMI displays over WiFi/TCP, BLE, and Serial. Not
tied to any specific device — originally developed against, and currently the reference client for,
the CrowPanel 4.2" e-paper display firmware (see the sibling `firmware-BSHMIEinkDevice` repo), but
designed to work against any device speaking the same wire protocol. The protocol itself (frame
envelope, all command payloads, TLV capability negotiation) is specified in `doc/PROTOCOL.md` in
the `firmware-BSHMIEinkDevice` repository — the main published asset it lives alongside — which is
the single source of truth this library must stay byte-for-byte consistent with; it is referenced
from there, not duplicated here.

## Package layout

`cz.bliksoft.hmieink.protocol` (unchanged from this library's original name, `bshmiprotocol` — only
the Maven `artifactId` was renamed to `bshmidriver`; the groupId/package were deliberately left
alone, to avoid an unnecessary breaking rename) holds **only** wire-protocol *definitions* — frame
envelope, transports, command dispatch, field-layout enums, TLV/handshake codec. Everything else —
the CLI, the integration entry point, and every PC-side tooling concern built on top of the protocol
(text notation, folder sync, script execution, image/font/macro file formats) — lives in top-level
siblings of `cz.bliksoft.hmieink`:

- `cz.bliksoft.hmieink` — `Cli`, the command-line front end, and `HmiUtils`, an integration entry
  point collapsing per-transport connect+handshake boilerplate (one nested static class per
  transport — `HmiUtils.Serial`/`.Tcp`/`.File`/`.Ble` — mirroring `HmiDevice`'s own
  subclass-per-transport isolation so e.g. calling `HmiUtils.Tcp.connect(...)` never forces
  jSerialComm/BSToolbox-BLE onto a TCP-only consumer's classpath; JVM class verification resolves
  every type any method of a class references as soon as that class loads, so this split — not a
  single flat class — is what actually preserves the isolation). `HmiUtils.Ble` also holds the BLE
  device-selector logic (`resolveDevice` for a single-match selector: `*`/`1`/substring/comma-list;
  `resolveExact` for the `=<exact>` selector, which can legitimately return several matches).
- `cz.bliksoft.hmieink.protocol` — frame envelope (`Frame`, `Crc16`, `RlePackBits` — RLE is real
  IMAGE_TRANSFER wire-payload encoding, doc/PROTOCOL.md §6, not just a file-format helper),
  `CommandClient` (SEQ assignment, ACK/NACK correlation, timeout+retry, event listeners,
  `LOG_MESSAGE` wait helpers), transports (`SerialFrameTransport`, `TcpFrameTransport`,
  `BleFrameTransport`, `FileFrameTransport`), `HmiDevice` and its transport-specific subclasses
  (`SerialHmiDevice`, `TcpHmiDevice`, `BleHmiDevice`, `FileHmiDevice`), `HandshakeCapabilities`,
  `AuthLevel`, `Tlv`/`TlvCodec`, and the ~30 single-purpose field enums (`Color`, `DrawMode`,
  `Volume`, `GpioMode`, `WriteFlags`, etc.), each mirroring one wire field/flags byte.
- `cz.bliksoft.hmieink.protocol.schema` — `CommandSchema` (the declarative, single-source-of-truth
  field layout for every command), `PayloadCodec` (the wire-encoding engine itself — encodes/decodes
  a command's payload bytes in exact wire order, deliberately agnostic of symbolic-name resolution,
  which is `.text`'s job), `FieldSpec`.
- `cz.bliksoft.hmieink.image` — `EpiImageCodec` (encoder/decoder for the `.epi` PC-side image file
  format staged before `FILE_UPLOAD`/`DRAW_IMAGE_DATA`, doc/PROTOCOL.md §12.7) and `IconSpecCache`
  (icon-spec-to-`.epi` rendering cache built on it, requiring the optional `common-java-utils`
  library).
- `cz.bliksoft.hmieink.macro` — `MacroCodec`, encoder/decoder for the `.macro` file format (a PC-side
  hand-authoring/recording convenience — the wire-relevant shape is the real
  RECORD_MACRO/SAVE_MACRO/PLAY_MACRO commands in `protocol`, not this file format).
- `cz.bliksoft.hmieink.text` — `TextCommandFormat` (bidirectional `NAME|field|field|...` textual
  command notation, schema-driven).
- `cz.bliksoft.hmieink.script` — `ScriptRunner`, the shared engine behind the CLI's `-f`/`-c`/`-p`
  flags and PC-local pseudo-commands (`SLEEP`, `WAIT_LOG`, `SYNC`, `ICONSPEC`). Uses `IconSpecCache`
  for icon spec processing, which requires the `common-java-utils` library.
- `cz.bliksoft.hmieink.sync` — `FolderSync`, recursive local-folder-vs-device-storage sync
  (PC-master/device-master/merge modes, all three volumes) built entirely on existing FILE_*
  commands - no protocol changes of its own. `RemoteFileStore` decouples the sync algorithm from
  the wire protocol for unit testing (`HmiDeviceRemoteFileStore` is the real, `HmiDevice`-backed
  implementation, using `HmiDevice`'s timeout-overriding file-method overloads with a generous 30s
  ceiling - real SD hardware can take longer than the 5s default for a large directory listing or
  bulk transfer, confirmed against a real ~220-file glyph set); `SyncManifest` tracks per-path
  content hashes for MERGE mode's change detection, since device storage has no timestamps to
  compare against. Exposed from the CLI as the `SYNC` pseudo-command (`ScriptRunner`).
- `cz.bliksoft.hmieink.font` — `GlyphGenerator`, rasterizes a TTF/system `Font` into the
  custom-font `.gly` glyph set `DRAW_TEXT FONT_ID=0xFF` consumes (firmware's doc/PROTOCOL.md
  §12.6.1), via plain `java.awt.Font`/`Graphics2D` (not BSToolbox's `IconSpecEngine`, a UI
  icon-compositing DSL with no TTF-rasterization support). `Codepages` builds byte-to-Unicode maps
  for single-byte charsets (e.g. CP1250) from the JDK's own `Charset`. `BdfFont` parses BDF bitmap
  fonts (e.g. Terminus).

## Build / test commands

```bash
mvn test              # compile + run the automated test suite (schema round-trip, text format, etc.)
```

Manual, real-hardware verification tools live under `src/test/java/.../protocol/manual/` (not part
of `mvn test`) — each is a `public static void main` you run directly against a real device over
one of the transports, e.g.:

```bash
mvn test-compile
java -cp target/classes;target/test-classes;<jSerialComm jar> \
    cz.bliksoft.hmieink.protocol.manual.StorageManualCheck COM5
```

`jSerialComm` is a `provided` dependency (only needed if you use `SerialFrameTransport`), so it
isn't on the default classpath — supply its jar explicitly as shown above.

## Provided dependencies

Four dependencies are deliberately `provided`, not required, so a consumer only pulls in what it
actually uses:
- `com.fazecast:jSerialComm` — only needed for `SerialFrameTransport`.
- `cz.bliksoft.java:common-java-utils-ble` (BSToolbox-BLE) — only needed for `BleFrameTransport`.
  On Maven Central as of 0.3.0.
- `info.picocli:picocli` — only needed to run the `Cli` class.
- `cz.bliksoft.java:common-java-utils` (BSToolbox) — only needed for the font-generator's
  `GenerateDeviceFonts` manual tool, and (via `IconSpecCache`) for `ScriptRunner`'s `ICONSPEC`
  pseudo-command. `IconSpecCache.isAvailable()` checks for it at runtime and `ICONSPEC` throws a
  clear `IOException` if it's missing, so `Cli` itself still runs without it.

## CLI distribution

`mvn package -Pdist` builds a standalone, runnable distribution of the `Cli` tool — the `dist`
profile is off by default so it has zero effect on `mvn install`/`mvn deploy` or the `release`
profile. Output: `target/bshmidriver-<version>/` (also zipped as `target/bshmidriver-<version>.zip`),
containing:
- `bshmidriver-cli.jar` — the library jar with a `Main-Class` manifest entry (`Cli`)
- `lib/` — the CLI's provided-scope runtime deps: jSerialComm, BSToolbox-BLE (+ its jackson
  transitives), picocli. Deliberately **not** `common-java-utils` — that's only needed for
  `ICONSPEC` command support in scripts (icon spec processing via `IconSpecCache`). The
  `common-java-utils` dependency also enables SVG processing and QR code generation for icon
  specs, but these are optional features — consumers who don't use `ICONSPEC` don't need them.
- `hmi-cli.sh` / `hmi-cli.bat` / `hmi-cli.command` — self-locating launch scripts (`java -cp <dir>/bshmidriver-cli.jar;<dir>/lib/*
  cz.bliksoft.hmieink.Cli "$@"`); pass all CLI args through unchanged. Note: no
  manifest `Class-Path`/`addClasspath` is used here — that maven-jar-plugin feature silently omits
  `provided`-scope deps, which all three of the above are.

Release process (manual, no CI — mirrors `BSMeshcoreCompanion`'s own release pattern, which has no
`.github/workflows/` either): build locally with `mvn package -Pdist`, then
`gh release create vX.Y.Z target/bshmidriver-*.zip` with release notes.

## Release process

Use the `prepare-maven-release` / `deploy-maven-release` / `deploy-maven-local` skills for
versioning and publishing — don't hand-edit the `<revision>` property or run `mvn deploy` directly.
`.forgejo/workflows/` files are the source of truth on GitHub (Forgejo pull-mirrors this repo); a
commit made only on the Forgejo side would be silently discarded on the next sync.

## Status

Verified end-to-end on real hardware (CrowPanel 4.2" e-paper display, via the sibling
`firmware-BSHMIEinkDevice` firmware): all three transports (Serial, WiFi/TCP, BLE) live-verified
simultaneously on one firmware image; the full drawing/storage/GPIO/OTA/power-management command
surface exercised via the manual check tools; the `HmiDevice`/`Cli` high-level layer confirmed
against real device responses. See `doc/PROTOCOL.md` §22 "Implementation status" in the
`firmware-BSHMIEinkDevice` repository for the exhaustive, per-feature verification history this
library's development produced.
