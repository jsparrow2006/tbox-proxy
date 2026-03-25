# TBox Proxy Library — Project Context

## Project Overview

**TBox Proxy Library** is a Kotlin/Android library that enables safe, concurrent access to a single TBox UDP connection from multiple applications or components. It solves the classic "only one process can bind to a UDP port" problem by providing a system-wide proxy service with automatic host election.

### Architecture

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
│              TCP Port 1104                                    │
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

### Key Features

- **Single UDP Socket**: Only one `DatagramSocket` binds to port `50047`
- **Automatic Host Election**: Any client can become the host if none exists
- **Failover Support**: If the host app crashes, another automatically takes over
- **TCP Bridge**: Uses TCP port `1104` for inter-process communication
- **Raw Data Delivery**: Receives `ByteArray` — you control parsing logic
- **No UI Dependencies**: Works in services, workers, or background tasks
- **Lightweight**: ~50 KB of actual code + minimal dependencies

### Library Size

The library is optimized for minimal size impact:

| Component | Size |
|-----------|------|
| **Source code** | ~50 KB (13 Kotlin files) |
| **Compiled .aar** | ~100-150 KB |
| **Dependencies** | ~300 KB (kotlinx-coroutines, androidx) |
| **Total impact** | **< 0.5 MB** in your APK |

**Note:** Previous versions included `cronet-embedded` (~25 MB) which was unused. This dependency has been removed.

### Dependencies

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")  // ~200 KB
implementation("androidx.annotation:annotation:1.7.1")                     // ~50 KB
implementation("androidx.core:core-ktx:1.12.0")                            // ~50 KB
```

Total: **~300 KB** of transitive dependencies (most are already in most Android apps).

## Project Structure

```
Tboxproxylib/
├── core/                          # Main library module
│   ├── src/main/
│   │   ├── java/dashingineering/jetour/tboxcore/
│   │   │   ├── TBoxClient.kt      # Main entry point
│   │   │   ├── constants/
│   │   │   │   └── TBoxConstants.kt
│   │   │   ├── discovery/
│   │   │   │   └── TcpDiscovery.kt
│   │   │   ├── service/
│   │   │   │   └── TBoxBridgeService.kt
│   │   │   ├── tcp/
│   │   │   │   └── TcpClient.kt
│   │   │   ├── types/
│   │   │   │   ├── LogType.kt
│   │   │   │   ├── TBoxClientCallback.kt
│   │   │   │   └── TBoxCommand.kt
│   │   │   ├── udp/
│   │   │   │   └── UdpHandler.kt
│   │   │   └── util/
│   │   │       ├── ByteConverter.kt
│   │   │       └── Extensions.kt
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── demo/                          # Demo application (2 flavors)
│   └── src/
│       └── demo1/                 # Demo1 app (com.dashing.tbox.proxy.demo)
│       └── demo2/                 # Demo2 app (com.dashing.tbox.proxy.demo2)
│   └── build.gradle.kts
├── gradle/                        # Gradle wrapper & version catalog
├── build.gradle.kts               # Root build configuration
├── settings.gradle.kts            # Project settings
├── gradle.properties              # Gradle properties
└── jitpack.yml                    # JitPack CI configuration
```

## Building and Running

### Prerequisites

- **JDK**: OpenJDK 17
- **Android SDK**: API 34 (compileSdk), API 24 (minSdk)
- **Android Gradle Plugin**: 8.x
- **Kotlin**: 1.8+

### Build Commands

```bash
# Build the library module
./gradlew :core:build

# Build the demo applications
./gradlew :demo:build

# Run tests (if any)
./gradlew :core:test

# Publish to local Maven (for JitPack)
./gradlew :core:publishToMavenLocal -x :demo:lintVitalDemo1Release -x :demo:lintVitalDemo2Release

# Clean build
./gradlew clean build
```

### Demo Flavors

The demo module has two product flavors for testing multi-app scenarios:

| Flavor | Package | App Name |
|--------|---------|----------|
| `demo1` | `com.dashing.tbox.proxy.demo` | TBox Demo |
| `demo2` | `com.dashing.tbox.proxy.demo2` | TBox Demo2 |

```bash
# Build specific flavor
./gradlew :demo:assembleDemo1Release
./gradlew :demo:assembleDemo2Debug
```

## Development Conventions

### Code Style

- **Kotlin Style**: Official (`kotlin.code.style=official`)
- **AndroidX**: Enabled (`android.useAndroidX=true`)
- **Non-Transitive R Class**: Enabled (`android.nonTransitiveRClass=true`)
- **JVM Target**: Java 17

### Project Configuration

From `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
kotlin.code.style=official
android.useAndroidX=true
android.nonTransitiveRClass=true
```

### Module Dependencies

**core/** dependencies:
```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
implementation("androidx.annotation:annotation:1.7.1")
implementation("androidx.core:core-ktx:1.12.0")
```

**demo/** dependencies:
```kotlin
implementation("androidx.core:core-ktx:1.10.0")
implementation(libs.androidx.appcompat)
implementation(libs.material)
implementation("androidx.activity:activity:1.7.0")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
implementation(project(":core"))
```

## Key Components

### TBoxClient

Main entry point for applications. Handles:
- Server discovery via TCP
- Automatic connection as client or starting as server
- Command sending with header/checksum management

### TBoxBridgeService

Foreground service that:
- Owns the UDP socket
- Bridges TCP clients to UDP
- Manages host election

### ByteConverter

Utility object for:
- `fillHeader()` — creates TBox protocol headers
- `xorSum()` — calculates checksum
- `toLogString()` — hex dump for logging

### TBoxConstants

Protocol constants:
```kotlin
const val CRT_CODE: Byte = 0x01
const val GATE_CODE: Byte = 0x10
// ... other protocol-specific constants
```

## Publishing

### JitPack Configuration

The library is published via JitPack. Configuration:

**jitpack.yml**:
```yaml
jdk:
  - openjdk17

install:
  - ./gradlew :core:publishToMavenLocal -x :demo:lintVitalDemo1Release -x :demo:lintVitalDemo2Release
```

**Publishing setup** (core/build.gradle.kts):
```kotlin
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "jsparrow2006"
            artifactId = "tboxcore"
            version = "1.0.0"
            from(components["release"])
        }
    }
}
```

## Usage Example

```kotlin
// Create client
val client = TBoxClient(
    context = applicationContext,
    callback = object : TBoxClientCallback {
        override fun onDataReceived(data: ByteArray) {
            // Handle incoming data
        }
        override fun onLogMessage(type: LogType, tag: String, message: String) {
            // Handle library logs
        }
        override fun onConnectionChanged(connected: Boolean) {
            // Handle connection status
        }
    }
)

// Initialize (auto-discovers or starts service)
client.initialize()

// Send commands
client.sendRawMessage(byteArrayOf(...))
client.sendCommand(TBoxConstants.CRT_CODE, TBoxConstants.GATE_CODE, 0x15, data)

// Cleanup
client.destroy()
```

## Required Permissions

Automatically added by the library:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

## Network Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `localPort` | 11048 | Local UDP port |
| `remotePort` | 50047 | TBox UDP port |
| `remoteAddress` | 192.168.225.1 | TBox IP address |
| `tcpPort` | 1104 | Inter-process TCP port |
| `host` | 127.0.0.1 | TCP connection host |

## Performance Optimizations

### Low-Latency Design (v1.1+)

The library has been optimized for minimal latency while maintaining Android best practices:

| Component | Optimization | Benefit |
|-----------|--------------|---------|
| **TcpClient.receiveLoop** | Blocking `read()` instead of `available() + delay(10)` | Instant data processing, no polling lag |
| **TcpClient callbacks** | Posted to Main thread via `Handler` | Safe UI updates, follows Android conventions |
| **TBoxClient.sendRawMessage** | Async with `scope.launch(Dispatchers.IO)` | Safe to call from UI thread |
| **UdpSocketManager.send** | `sendMutex` protects concurrent sends | Prevents packet interleaving |

### Callback Threading

All callbacks (`onDataReceived`, `onLogMessage`, `onConnectionChanged`) are delivered on the **Main thread**:

```kotlin
val client = TBoxClient(
    context = applicationContext,
    callback = object : TBoxClientCallback {
        override fun onDataReceived(data: ByteArray) {
            // ✅ Safe to update UI directly
            recyclerView.adapter?.submitList(parseData(data))
        }

        override fun onLogMessage(type: LogType, tag: String, message: String) {
            // ✅ Safe to update UI directly
            logTextView.text = message
        }

        override fun onConnectionChanged(connected: Boolean) {
            // ✅ Safe to update UI directly
            connectionIndicator.setImageResource(
                if (connected) R.drawable.ic_connected else R.drawable.ic_disconnected
            )
        }
    }
)
```

**Note:** Callback latency is typically <5ms (Main thread scheduling + data processing).

### Concurrent Send Behavior

When multiple apps send commands simultaneously:

```
App1.sendCommand() ──┐
                     ├──→ scope.launch(IO) ──→ TcpClient.sendMutex ──→ Sequential UDP sends
App2.sendCommand() ──┘                                              (no packet loss)
```

- **Order preserved**: Commands are sent in the order they acquire the mutex
- **No blocking**: UI threads are never blocked when sending
- **No packet loss**: Mutex ensures atomic UDP sends
- **Callbacks on Main**: All responses delivered on Main thread for safe UI updates

### Usage Best Practices

```kotlin
// ✅ Simple UI usage — no boilerplate needed
button.setOnClickListener {
    tboxClient.sendCommand(tid, sid, cmd, data)
}

// ✅ Rapid sequential sends — queued automatically
tboxClient.sendCommand(...)
tboxClient.sendCommand(...)
tboxClient.sendCommand(...)

// ✅ High-frequency data requests — use with response tracking
data class PendingRequest(val command: TBoxCommand, val timestamp: Long)
private val pendingRequests = ConcurrentHashMap<Int, PendingRequest>()

fun sendWithTracking(command: TBoxCommand) {
    pendingRequests[command.tid] = PendingRequest(command, System.currentTimeMillis())
    tboxClient.sendCommand(command)
}
```
