# TBox Proxy Library — Agent Guide

> This file is intended for AI coding agents. It assumes you know nothing about this project.

## Project Overview

**TBox Proxy Library** is a Kotlin/Android library that solves the "only one process can bind to a UDP port" problem for TBox (telematics box) communication. It provides a system-wide proxy service with automatic host election, allowing multiple apps to share a single UDP connection to a TBox device.

- **Library module**: `:core` (`dashingineering.jetour.tboxcore`)
- **Demo module**: `:demo` (`com.dashing.tbox.proxy.demo`) — an Android app with two product flavors (`demo1`, `demo2`) for testing multi-app scenarios
- **Distribution**: Published via JitPack
- **License**: MIT

### Problem Solved

Only one `DatagramSocket` can listen on port `50047` at a time. Multiple apps (diagnostics, telemetry, UI) need to send commands and receive data from the TBox. If the host app crashes, another must automatically take over without data loss.

### Runtime Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Multiple Applications                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                    │
│  │  App 1   │  │  App 2   │  │  App 3   │                    │
│  │ (Demo1)  │  │ (Demo2)  │  │  (UI)    │                    │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘                    │
│       │             │             │                           │
│       └─────────────┴─────────────┘                           │
│                     │                                         │
│              TCP Port 1104 (loopback)                         │
│                     │                                         │
│       ┌─────────────▼─────────────┐                          │
│       │   TBoxBridgeService       │ ← Foreground Service     │
│       │   (owns UDP socket)       │   (single instance)      │
│       └─────────────┬─────────────┘                          │
│                     │                                         │
│              UDP Port 50047                                   │
│                     │                                         │
│       ┌─────────────▼─────────────┐                          │
│       │      TBox Device          │                          │
│       │   (192.168.225.1)         │                          │
│       └───────────────────────────┘                          │
└─────────────────────────────────────────────────────────────┘
```

**Flow**: `TBoxClient` → `TcpDiscovery` checks port 1104. If a host exists, connects as `TcpClient`. Otherwise starts `TBoxBridgeService`, which runs `UdpSocketManager` + `TcpServer`, then connects to localhost as `TcpClient`.

## Technology Stack

| Technology | Version | Notes |
|-----------|---------|-------|
| Kotlin | 1.9.0 | |
| Android Gradle Plugin | 8.6.0 | |
| Compile SDK | 34 | `compileSdkExtension = 11` |
| Min SDK | 24 | |
| Target SDK | 34 | (demo only) |
| JVM Target | 17 | Source & target compatibility |
| Kotlin Coroutines | 1.8.1 | `kotlinx-coroutines-android` |
| Kotlin Serialization | 1.6.0 | (demo only) |

### Key Dependencies (core)

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
implementation("androidx.annotation:annotation:1.7.1")
implementation("androidx.core:core-ktx:1.12.0")
```

## Project Structure

```
Tboxproxylib/
├── core/                              # Main Android library module
│   ├── src/main/java/dashingineering/jetour/tboxcore/
│   │   ├── TBoxClient.kt              # Main public API entry point
│   │   ├── constants/
│   │   │   └── TBoxConstants.kt       # Protocol byte codes (CRT_CODE, GATE_CODE, etc.)
│   │   ├── discovery/
│   │   │   └── TcpDiscovery.kt        # Probes localhost TCP port to find existing host
│   │   ├── service/
│   │   │   └── TBoxBridgeService.kt   # Android foreground service owning UDP + TCP server
│   │   ├── tcp/
│   │   │   ├── TcpClient.kt           # TCP client (connects to bridge service)
│   │   │   ├── TcpServer.kt           # TCP server (accepts multiple local clients)
│   │   │   └── FrameCodec.kt          # Length-prefixed framing for TCP streams
│   │   ├── types/
│   │   │   ├── LogType.kt             # DEBUG, INFO, WARN, ERROR, VERBOSE
│   │   │   ├── TBoxCallback.kt        # Internal callback interface (ByteArray)
│   │   │   ├── TBoxClientCallback.kt  # Public callback interface (TBoxReceivedMessage)
│   │   │   └── TBoxCommand.kt         # Data class for structured commands
│   │   ├── udp/
│   │   │   └── UdpSocketManager.kt    # DatagramSocket wrapper (send/receive)
│   │   └── util/
│   │       ├── ByteConverter.kt       # Protocol utilities: fillHeader, xorSum, hex conversions
│   │       ├── Extensions.kt          # startForegroundServiceCompat, hex helpers
│   │       └── TBoxReceivedMessage.kt # Wrapper parsing raw bytes into tid/sid/command/payload
│   ├── src/main/AndroidManifest.xml   # Declares service + required permissions
│   ├── build.gradle.kts               # Library build config + maven-publish
│   ├── consumer-rules.pro             # (empty)
│   └── proguard-rules.pro             # (template)
│
├── demo/                              # Demo application module
│   ├── src/main/java/dashengineering/jetour/TboxCore/demo/
│   │   ├── MainActivity.kt            # Sample usage with RecyclerView log UI
│   │   └── PacketAdapter.kt           # RecyclerView adapter for log packets
│   ├── src/main/AndroidManifest.xml
│   └── build.gradle.kts               # Two product flavors: demo1, demo2
│
├── gradle/libs.versions.toml          # Version catalog
├── settings.gradle.kts                # Includes :core and :demo
├── build.gradle.kts                   # Root build file (plugins only)
├── gradle.properties                  # AndroidX, Kotlin code style, JVM args
└── jitpack.yml                        # JitPack CI: JDK 17 + publishToMavenLocal
```

### Language Notes

- **Project documentation** (README, this file) is written in **English**.
- **Inline code comments** inside Kotlin source files are primarily in **Russian**.
- There is a package name inconsistency: the core library uses `dashingineering` while the demo app uses `dashengineering`.

## Build and Test Commands

```bash
# Build the library AAR
./gradlew :core:build

# Build the demo app (both flavors)
./gradlew :demo:build

# Build a specific demo flavor
./gradlew :demo:assembleDemo1Debug
./gradlew :demo:assembleDemo2Debug

# Run unit tests (core module has no meaningful tests currently)
./gradlew :core:test

# Run instrumented tests (demo module only has example tests)
./gradlew :demo:connectedAndroidTest

# Publish library to local Maven (same command JitPack uses)
./gradlew :core:publishToMavenLocal -x :demo:lintVitalDemo1Release -x :demo:lintVitalDemo2Release

# Clean everything
./gradlew clean
```

### Demo Flavors

| Flavor | Application ID | App Name |
|--------|---------------|----------|
| `demo1` | `com.dashing.tbox.proxy.demo` | TBox Demo |
| `demo2` | `com.dashing.tbox.proxy.demo2` | TBox Demo2 |

Install both to test multi-app host election and failover on a single device.

## Code Style Guidelines

- **Kotlin code style**: Official (`kotlin.code.style=official` in `gradle.properties`)
- **AndroidX**: Enabled (`android.useAndroidX=true`)
- **Non-transitive R class**: Enabled (`android.nonTransitiveRClass=true`)
- **JVM target**: Java 17
- Coroutines for all async/network work; **never block the Main thread**.
- Callbacks are always posted to the **Main thread** via `Handler(Looper.getMainLooper())` or `Dispatchers.Main`.

## Key Conventions

### Threading Model

| Layer | Dispatcher / Thread | Responsibility |
|-------|---------------------|----------------|
| `TBoxClient` public API | Main (caller) | Accepts calls from UI, queues to Channel |
| Send queue processor | `Dispatchers.IO` | Drains `Channel<ByteArray>` sequentially |
| `TcpClient` I/O | `Dispatchers.IO` | Blocking socket reads/writes |
| `TcpServer` I/O | `Dispatchers.IO` | Blocking `accept()` and client handlers |
| `UdpSocketManager` | `Dispatchers.IO` | Blocking `DatagramSocket.receive()` |
| All callbacks | Main thread | Safe for UI updates |

### Command Sending

Commands are **queued** via a `Channel<ByteArray>(Channel.UNLIMITED)` and sent sequentially. This means:
- Calling `sendRawMessage()` or `sendCommand()` from the UI thread is safe.
- Order is preserved.
- Multiple concurrent callers do not interleave packets.

### Framing Protocol

TCP communication uses a simple length-prefix framing (`FrameCodec`):
- **Encode**: 4 bytes (Int, big-endian length) + payload
- **Decode**: Read 4 bytes → parse length → read payload
- Max frame size: 64 KB

### Default Network Parameters

| Parameter | Default Value | Purpose |
|-----------|--------------|---------|
| `localPort` | `11048` | Local UDP bind port |
| `remotePort` | `50047` | TBox device UDP port |
| `remoteAddress` | `192.168.225.1` | TBox device IP |
| `tcpPort` | `1104` | Localhost TCP bridge port |
| `host` | `127.0.0.1` | TCP connection address |

## Testing Instructions

> **Current test coverage is minimal.** The project contains only placeholder/example tests:
> - `demo/src/test/.../ExampleUnitTest.kt` — trivial JUnit4 test
> - `demo/src/androidTest/.../ExampleInstrumentedTest.kt` — trivial instrumented test
>
> There are **no unit tests** for `TBoxClient`, `TBoxBridgeService`, `TcpServer`, `TcpClient`, `UdpSocketManager`, or `FrameCodec`.

When adding tests:
- Use **JUnit 4** (already configured).
- For instrumented tests, use `AndroidJUnit4` runner.
- Mock Android framework classes (`Context`, `Service`, etc.) or use Robolectric if testing service logic without a device.
- `FrameCodec` and `ByteConverter` are pure Kotlin and can be tested with plain JVM unit tests.

## Security Considerations

### Permissions Declared in Library Manifest

The `core` module's `AndroidManifest.xml` automatically merges these permissions into consuming apps:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
```

> **Note**: `WRITE_EXTERNAL_STORAGE` and `READ_EXTERNAL_STORAGE` are declared in the library manifest even though the library itself does not perform file I/O. The demo app uses them for log saving. This causes permission requirements to propagate to all library consumers. Be aware of this when modifying permissions.

### Service Security

- `TBoxBridgeService` is **`exported="false"`** — only the host app (or apps sharing the same UID) can interact with it directly via Android binder.
- Inter-app communication happens over **localhost TCP** (port 1104), not via exported components.
- There is **no authentication** on the localhost TCP socket. Any process on the device can connect to port 1104 and send/receive TBox data. This is acceptable for the intended automotive/embedded use case but should be noted if the threat model changes.

### ProGuard / R8

- `consumer-rules.pro` is empty. The library does not ship custom ProGuard rules.
- `proguard-rules.pro` in the core module is a stock template with no active rules.

## Publishing

The library is published via **JitPack**:

- `jitpack.yml` specifies OpenJDK 17 and runs `./gradlew :core:publishToMavenLocal`.
- `core/build.gradle.kts` registers a `MavenPublication` named `release` with `groupId = "jsparrow2006"`, `artifactId = "tboxcore"`.
- The actual version consumed via JitPack is derived from the Git tag (e.g., `v1.0.4`), but a local placeholder (`1.0.0`) is used in the build script.

## Common Pitfalls

1. **Host election race condition**: `TcpDiscovery` uses a 300 ms timeout. On very slow devices, a newly-started service might not be listening yet when a second app checks, causing both apps to attempt to start a service. The second `ServerSocket` bind will fail, and the app will eventually retry.
2. **Service lifecycle**: `TBoxClient.destroy()` stops the service only if `isServerMode == true`. If an app connected as a pure client, destroying its `TBoxClient` does not affect the shared bridge service.
3. **Buffer reuse in `FrameCodec`**: `TcpClient.receiveLoop` and `ClientHandler.receive` reuse a single `ByteArray` buffer and compact remaining bytes manually. Off-by-one errors here would corrupt the TCP stream.
4. **No retry logic**: If the TCP connection drops, there is no automatic reconnection. The consuming app must detect `onConnectionChanged(false)` and call `initialize()` again.
