# CLI Command Reference

The `bshmidriver` CLI is a standalone scriptable controller for HMI devices speaking the CrowPanel protocol. It can control devices over Serial, WiFi/TCP, BLE, or record commands to a file.

## Overview

The CLI accepts commands via three methods that can be mixed in any order on the command line:
- `-c` / `--command` - inline commands
- `-f` / `--file` - commands from a file
- `-p` / `--pipe` - commands from stdin

Commands are sent to the device in the exact order they appear on the command line. The `-s` / `--separator` option lets you change the field separator character (default is `|`).

## Classpath Requirements

The CLI jar is a library jar without embedded dependencies. You must supply transport-specific dependencies on the classpath based on which transport you use:

| Transport | Required Dependency | Scope |
|-----------|--------------------|-------|
| TCP | None (uses JDK only) | Built-in |
| Serial | `com.fazecast:jSerialComm` | `provided` in POM |
| BLE | `cz.bliksoft.java:common-java-utils-ble` (BSToolbox-BLE) | `provided` in POM |
| `ICONSPEC` command | `cz.bliksoft.java:common-java-utils` | `provided` in POM |

**Note:** TCP connections work with just the CLI jar. All other transports and features require additional libraries.

## Quick Start

### Connection Specification

**TCP (WiFi):**
```bash
java -jar bshmidriver-cli.jar -t tcp -a 192.168.1.100:8080 -c "FAST_CLEAR|WHITE|0"
```

**Serial:**

For serial connections, include jSerialComm on the classpath:

```bash
# Using the distributed package (recommended):
./hmi-cli.sh -t serial -a COM5 -c "FAST_CLEAR|WHITE|0"

# Or manually with jSerialComm:
java -jar bshmidriver-cli.jar -cp <path/to/jSerialComm.jar> -t serial -a COM5 -c "FAST_CLEAR|WHITE|0"
```

**BLE:**

For BLE connections, include BSToolbox-BLE (and its dependencies) on the classpath:

```bash
# Using the distributed package (recommended):
./hmi-cli.sh -t ble -a "*" -c "FAST_CLEAR|WHITE|0"

# Or manually with BSToolbox-BLE:
java -jar bshmidriver-cli.jar -cp <path/to/bstoolbox-ble.jar> -t ble -a "*" -c "FAST_CLEAR|WHITE|0"
```

**File (record/replay):**
```bash
java -jar bshmidriver-cli.jar -t file -a mycommands.macro -c "FAST_CLEAR|WHITE|0"
```

### Using the Distributed CLI Package

The recommended way to run the CLI is using the standalone distribution built with `mvn package -Pdist`. This creates:

```
target/bshmidriver-<version>/
├── bshmidriver-cli.jar          # Main CLI jar
├── lib/                         # All required dependencies
│   ├── jSerialComm.jar
│   ├── common-java-utils-ble.jar
│   └── ...
└── hmi-cli.sh / hmi-cli.bat     # Launch scripts
```

The launch scripts (`hmi-cli.sh`, `hmi-cli.bat`, `hmi-cli.command`) automatically set up the classpath with all transport dependencies. Use them instead of manually invoking `java -jar`:

```bash
./hmi-cli.sh -t serial -a COM5 -c "FAST_CLEAR|WHITE|0"
hmi-cli.bat -t ble -a "*" -c "FAST_CLEAR|WHITE|0"
```

### Authentication

The CLI supports the protocol's handshake authentication (doc/PROTOCOL.md §5.3):

```bash
# Usage-level authentication
java -jar bshmidriver-cli.jar -t serial -a COM5 -k "1234" -c "DRAW_RECT|..."

# Admin-level authentication (implies usage)
java -jar bshmidriver-cli.jar -t serial -a COM5 -K "5678" -c "DRAW_RECT|..."
```

## Command Format

### Wire Protocol vs. Text Format

Commands on the wire are binary frames with this envelope (doc/PROTOCOL.md §3):

| Field | Size | Description |
|-------|------|-------------|
| Magic | 2 bytes | `0x42`, `0x53` ("BS") |
| Version | 1 byte | Protocol version |
| Command ID | 2 bytes | Big-endian |
| Payload length | 2 bytes | Big-endian |
| Payload | N bytes | Command-specific data |
| CRC-16 | 2 bytes | CRC-16/CCITT of all previous bytes |

The **text format** is a human-readable representation of the command's payload fields:

```
COMMAND_NAME|field1|field2|field3|...
```

The CLI's `TextCommandFormat` class drives this format using the `CommandSchema` definition, ensuring it never drifts from the wire protocol.

### Text Format Details

- **Separator**: Default is `|` (can be changed with `-s` option)
- **Field order**: Matches the wire protocol's payload field order
- **String fields**: Accept backslash escapes:
  - `\n` - newline (0x0A)
  - `\t` - tab (0x09)
  - `\\` - literal backslash
  - `\<separator>` - literal separator character
  - `\uXXXX` - Unicode code unit (4 hex digits)
- **Number fields**: Decimal or `0x`-prefixed hex
- **Boolean fields**: `true`/`false` (case-insensitive)
- **Enum fields**: Symbolic name (e.g., `WHITE`, `REPLACE`) or raw integer
- **Bitmask fields**: Multiple names joined with `+` (e.g., `REFRESH_NOW+DOUBLE_BUFFER`)
- **BYTES fields**: UTF-8 text or `@<filepath>` to read raw bytes from a file

### Example Command Translation

Consider the `DRAW_RECT` command:

| Wire Protocol Field | Text Format Field | Example |
|---------------------|-------------------|---------|
| `x` (U16LE) | x | `10` |
| `y` (U16LE) | y | `20` |
| `width` (U16LE) | width | `100` |
| `height` (U16LE) | height | `60` |
| `color` (U16LE, enum) | color | `BLACK` or `0x0000` |
| `drawMode` (U8, enum) | drawMode | `REPLACE` |
| `useClipRegion` (U8, bool) | useClipRegion | `true` |
| `lineWidth` (U8) | lineWidth | `2` |
| `refreshFlags` (U8, bitmask) | refreshFlags | `REFRESH_NOW` |

Resulting text command:
```
DRAW_RECT|10|20|100|60|BLACK|REPLACE|true|2|REFRESH_NOW
```

## PC-Local Pseudo-Commands

These commands control script execution but are **never sent to the device**:

### `SLEEP`

Blocks the script for a specified duration (milliseconds):

```
SLEEP|1000
```

This pauses script execution for 1 second without affecting the device.

### `WAIT_LOG`

Waits for a `LOG_MESSAGE` frame from the device with an optional marker:

```
WAIT_LOG|5000                    # Wait up to 5 seconds for any log message
WAIT_LOG|5000|macro_completed    # Wait for log message with "macro_completed" marker
```

The script blocks until:
- A `LOG_MESSAGE` frame is received, OR
- The timeout expires (throws `IOException`)

Use the `WAIT_LOG|<marker>` variant when the firmware sends a specific marker in its `LOG_MESSAGE` to signal completion of an operation (e.g., macro playback finished).

### `SYNC`

Recursively synchronizes a local folder with device storage. This is **not a single wire command** - the CLI orchestrates multiple `FILE_LIST`, `DOWNLOAD`, `UPLOAD`, and `DELETE` commands as needed.

```
SYNC|<localDir>|<volume: SD|INTERNAL|PSRAM>|<devicePath>|<mode>
```

**Modes:**
- `PC_MASTER` - Local changes win; device is made to match PC
- `DEVICE_MASTER` - Device changes win; PC is made to match device
- `MERGE` - Combine both sides, resolving conflicts manually

**Example:**
```bash
# Sync local glyphs folder to device's SD card /glyphs directory
SYNC|./glyphs|SD|/glyphs|PC_MASTER
```

The sync manifest (tracking file content hashes for MERGE mode) is stored as a sibling file:
```
<localDir-name>.bshmisync-manifest
```

### `ICONSPEC`

Generates an image from an icon spec string, converts it to the device's binary B/W `.epi` format
(with a transparency mask if the source image has any pixels with alpha below 128), and caches it
under a name:

```
ICONSPEC|<name>|<spec>
```

The cached image can then be referenced in subsequent commands as `#<name>` (e.g. as a
`DRAW_IMAGE` payload) instead of an `@<filepath>` - `TextCommandFormat`'s `BYTES` field parsing
resolves it directly from the in-memory cache, so it never touches disk.

**Requires** the `cz.bliksoft.java:common-java-utils` dependency on the classpath (see
[Classpath Requirements](#classpath-requirements) above) - without it, `ICONSPEC` fails with an
`IOException` explaining what's missing, but the rest of the script/CLI still runs fine.

Use `-i` / `--image-root` to set the root directory (or classpath root) icon specs resolve
relative image paths against:

```bash
./hmi-cli.sh -t serial -a COM5 -i ./branding-images -f draw.macro
```

**Example:**
```
ICONSPEC|logo|<icon spec string>
DRAW_IMAGE|0|0|#logo|REFRESH_NOW
```

## Full Command Catalog

See the firmware protocol specification (`doc/PROTOCOL.md` in the `firmware-BSHMIEinkDevice` repository) for complete command details. The following are the main command categories:

### Drawing Commands (§7)
- `FAST_CLEAR` - Quick full-screen clear
- `DRAW_LINE`, `DRAW_RECT`, `DRAW_CIRCLE` - Basic primitives
- `DRAW_TEXT` - Text rendering with custom fonts
- `DRAW_IMAGE` - Full image rendering
- `DRAW_IMAGE_ROW` - Row-by-row image transfer (for large images)
- `CLEAR_REGION`, `FILL_IMAGE` - Region operations
- `REFRESH` - Trigger display refresh

### Storage Commands (§8)
- `FILE_LIST`, `FILE_DOWNLOAD`, `FILE_UPLOAD`, `FILE_DELETE`
- `FILE_COPY`, `FILE_RENAME`
- `STORAGE_INFO` - Query storage capacity

### GPIO Commands (§9)
- `GPIO_CONFIGURE`, `GPIO_WRITE`, `GPIO_READ`
- `GPIO_EVENT`, `GPIO_PLAY_PATTERN`

### OTA Commands (§11)
- `OTA_INSTALL`, `OTA_APPLY`, `OTA_STATUS`
- `OTA_CONFIRM`, `OTA_ROLLBACK`

### Configuration Commands (§6)
- `SET_WIFI_CONFIG`, `SET_BLE_PIN`, `SET_DEVICE_NAME`
- `SET_USAGE_PIN`, `SET_ADMIN_PIN`

### Macro Commands (§12)
- `RECORD_MACRO`, `SAVE_MACRO`, `PLAY_MACRO`
- `PAUSE`

## Examples

### Simple Script File (`draw.macro`)

```
# Clear screen and draw a rectangle
FAST_CLEAR|WHITE|0
DRAW_RECT|10|10|100|60|BLACK|REPLACE|true|2|REFRESH_NOW

# Wait for macro to complete (if this were a recorded macro)
WAIT_LOG|5000|macro_completed
```

Run with:
```bash
./hmi-cli.sh -t serial -a COM5 -f draw.macro
```

### Multi-Device Script

```bash
# Scan for BLE devices, then run the same script on each
./hmi-cli.sh -t ble -a "*" -f sequence.macro
```

### Complex Script with Synchronization

```
# Sync fonts to device first
SYNC|./fonts|SD|/fonts|PC_MASTER

# Set the custom font folder
SET_CUSTOM_FONT_FOLDER|/fonts

# Draw some text
DRAW_TEXT|50|50|Hello World|0xFF|WHITE|REFRESH_NOW

# Wait for refresh to complete
WAIT_LOG|5000|refresh_done

# Upload an image and display it
DRAW_IMAGE|0|0|@./logo.bin|REFRESH_NOW
```

## Integration with Other Tools

The CLI can be combined with shell scripting:

```bash
# Read IP from file and run commands
IP=$(cat device_ip.txt)
./hmi-cli.sh -t tcp -a "$IP:8080" -f commands.macro

# Pipe commands from a generated script
echo -e "FAST_CLEAR|WHITE|0\nDRAW_RECT|10|10|100|60|BLACK|REPLACE|true|2|REFRESH_NOW" | \
    ./hmi-cli.sh -t serial -a COM5 -p
```

## Building the Distribution

To create a standalone CLI distribution with all required dependencies:

```bash
mvn package -Pdist
```

This creates `target/bshmidriver-<version>/` (also zipped as `target/bshmidriver-<version>.zip`) containing:
- `bshmidriver-cli.jar` - the main application jar
- `lib/` - all required dependencies (jSerialComm, BSToolbox-BLE, picocli)
- Launch scripts for Windows (`hmi-cli.bat`), Linux/macOS (`hmi-cli.sh`), and macOS (`hmi-cli.command`)

## Exit Codes

- `0` - Success
- `1` - Error (connection failure, command error, sync conflicts)
- `2` - Usage error (invalid arguments, missing required options)

## See Also

- **Firmware Protocol Specification**: `doc/PROTOCOL.md` in the `firmware-BSHMIEinkDevice` repository
- **Package Layout**: `CLAUDE.md` in this repository
- **Java API**: `HmiDevice` class and subclasses in `cz.bliksoft.hmieink.protocol`
