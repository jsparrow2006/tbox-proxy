# TBox Proxy Library

A Kotlin/Android library that enables **safe, concurrent access to a single TBox UDP connection** from multiple applications or components. It solves the classic **"only one process can bind to a UDP port"** problem by providing a system-wide proxy service with automatic host election.

## Problem Solved

- Only **one `DatagramSocket`** can listen on port `50047` at a time.
- Multiple apps (e.g., diagnostics, telemetry, UI) need to **send commands and receive data** from the TBox.
- If the host app crashes, another should **automatically take over** without data loss.

This library provides:
- A **single foreground service** (`TBoxBridgeService`) that owns the UDP socket.
- **Automatic host election**: any client can become the host if none exists.
- **Zero boilerplate**: no need to write your own service or manage sockets.
- **Auto-reconnection**: automatically reconnects when TBox stops responding.
- **Status monitoring**: typed status events for connection state, errors, and diagnostics.

---

## Installation

### 1. Add JitPack to your `settings.gradle.kts` (or `settings.gradle`)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. Add the dependency to your app's `build.gradle.kts`

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.jsparrow2006:tbox-proxy:v1.1.0")
}
```

or

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.jsparrow2006:tbox-proxy:v1.+")
}
```

You can find all released versions [here](https://github.com/jsparrow2006/tbox-proxy/releases)

Requires Kotlin >= 1.8 and AGP >= 8.0.

---

## Usage

### 1. Create a client instance

```kotlin
val client = TBoxClient(
    context = applicationContext,
    callback = myCallback
)
```

Or with custom parameters

```kotlin
val client = TBoxClient(
    context = applicationContext,
    localPort = 11048,
    remotePort = 50047,
    remoteAddress = "192.168.225.1",
    tcpPort = 1104,
    callback = myCallback
)
```

### 2. Set up event handlers

```kotlin
val client = TBoxClient(
    context = applicationContext,
    callback = object : TBoxClientCallback {
        override fun onDataReceived(message: TBoxReceivedMessage) {
            // Received data from TBox
            // Raw ByteArray: message.getRawData()
            // Log string: message.getRawData().toLogString()
        }

        override fun onLogMessage(type: LogType, tag: String, message: String) {
            // Internal library logs (including service-side UDP/TCP logs)
        }

        override fun onConnectionChanged(connected: Boolean) {
            // true  = TBox is physically responding with data
            // false = connection lost (auto-reconnect will start)
        }

        override fun onStatusChanged(status: TBoxStatus) {
            // Typed status events from the library
            when (status.type) {
                TBoxStatusType.CONNECTING -> // TCP connected, waiting for TBox response
                TBoxStatusType.CONNECTED -> // TBox is physically responding
                TBoxStatusType.DISCONNECTED -> // Connection lost
                TBoxStatusType.UDP_BIND_FAILED -> // Port already in use
                TBoxStatusType.UDP_RECEIVE_ERROR -> // TBox not responding (watchdog timeout)
                TBoxStatusType.SERVICE_STARTED -> // Bridge service started
                TBoxStatusType.SERVICE_STOPPED -> // Bridge service stopped
                // ... see TBoxStatusType for all types
            }
        }
    }
)
```

> **Note:** `onStatusChanged` has a default empty implementation — existing code will compile without changes.

### 3. Connect and start receiving data

```kotlin
client.initialize()
```

On first launch (no host running), the library automatically starts its own service and connects to the TBox using the provided IP/port.

Subsequent apps simply subscribe to the existing host.

**Connection lifecycle:**
1. `initialize()` starts TCP discovery
2. If no existing host found, starts `TBoxBridgeService`
3. Service binds UDP socket and sends a wake-up command to TBox
4. `onStatusChanged(CONNECTING)` fires when TCP is connected
5. `onConnectionChanged(true)` fires **only when TBox responds with data**
6. `isConnected()` returns `true` only after TBox physically responds
7. If TBox stops responding (5s timeout), watchdog disconnects and auto-reconnects

### 4. Send commands to TBox

Send raw ByteArray:
```kotlin
client.sendRawMessage(byteArrayOf(...))
```

Send with parameters:
```kotlin
client.sendCommand(TBoxConstants.CRT_CODE, TBoxConstants.GATE_CODE, 0x15, byteArrayOf(0x01, 0x02))
```

Send with TBoxCommand object:
```kotlin
val getCanFrames = TBoxCommand(
    tid = TBoxConstants.CRT_CODE,
    sid = TBoxConstants.GATE_CODE,
    cmd = 0x15,
    data = byteArrayOf(0x01, 0x02)
)
client.sendCommand(getCanFrames)
```

> **Important:** Commands should be sent only after `onConnectionChanged(true)` fires. Sending before TBox is physically connected will result in messages being queued but may not reach the device.

### 5. Clean up

```kotlin
override fun onDestroy() {
    tboxClient.destroy()
    super.onDestroy()
}
```

`destroy()` stops auto-reconnection and releases all resources.

---

## Status Types

The library provides typed status events via `TBoxStatusType`:

| Type | Description |
|------|-------------|
| `CONNECTING` | TCP connected, waiting for TBox physical response |
| `CONNECTED` | TBox is responding with data |
| `DISCONNECTED` | Connection lost |
| `UDP_BIND_SUCCESS` | UDP socket bound successfully |
| `UDP_BIND_FAILED` | UDP port already in use |
| `UDP_RECEIVE_ERROR` | TBox not responding (watchdog timeout) |
| `UDP_SEND_ERROR` | Failed to send UDP packet |
| `SERVICE_STARTED` | Bridge service fully started |
| `SERVICE_STOPPED` | Bridge service stopped |
| `SERVICE_ERROR` | Bridge service failed to start |
| `TCP_SERVER_STARTED` | TCP server started |
| `TCP_SERVER_ERROR` | TCP server failed to start |
| `LOG` | Internal log message (forwarded to `onLogMessage`) |

---

## Auto-Reconnection

The library handles connection loss automatically:

1. **Watchdog** monitors UDP data flow (TBox sends data every ~1 second)
2. If no data received for 5 seconds, the bridge service shuts down
3. TCP connection breaks, triggering `onConnectionChanged(false)`
4. After 3 seconds, the library automatically attempts to reconnect
5. Race condition: multiple clients compete to become the new host (first one wins)

To stop reconnection, call `tboxClient.destroy()`.

---

## Required Permissions

The library automatically adds to your manifest:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

Ensure your app has permission to run foreground services (especially on Android 12+).

---

## Key Features

- **Seamless failover**: if the host dies, any client can become the new host.
- **Auto-reconnection**: automatically reconnects when TBox stops responding.
- **Watchdog monitoring**: detects TBox unavailability within 5 seconds.
- **Typed status events**: know exactly what's happening via `TBoxStatusType`.
- **Service log forwarding**: all internal logs (UDP, TCP, watchdog) visible via `onLogMessage`.
- **Raw data delivery**: receives `ByteArray` — you control parsing logic.
- **No UI dependencies**: works in services, workers, or background tasks.
- **Self-contained**: includes all protocol utilities (`fillHeader`, `xorSum`, etc.).

---

Utility functions like `fillHeader`, `xorSum`, and `extractData` are available in `dashingineering.jetour.tboxcore.util`.

---

## License

MIT License. See [LICENSE](LICENSE)
