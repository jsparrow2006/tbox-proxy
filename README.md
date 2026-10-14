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
    implementation("com.github.jsparrow2006:tbox-proxy:v3.0.0")
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
    callback = myCallback,
    v3TcpPort = 1105,
    bindToTBoxNetwork = true
)
```

`bindToTBoxNetwork` binds the host's UDP socket to the network that owns the TBox subnet
(`usb0`). Without it, Android 10 head units with Wi-Fi enabled route TBox traffic to `wlan0`.

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
1. `initialize()` looks for a 3.x host on port 1105, then for a 1.x/2.x host on port 1104
2. If no host is found, starts `TBoxBridgeService`
3. Service binds the UDP socket to the TBox network and sends a wake-up command to TBox
4. `onStatusChanged(CONNECTING)` fires when TCP is connected
5. `onConnectionChanged(true)` fires **only when TBox responds with data**
6. `isConnected()` returns `true` only after TBox physically responds
7. If TBox is silent for 10 s, `onConnectionChanged(false)` fires; the bridge keeps running and probes TBox

`getHostInfo()` / `getWireProtocol()` tell which host the client is connected to.
`getMode()` returns `SERVER` when the bridge runs in this app.

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
| `NETWORK_BOUND` | UDP socket bound to the TBox network (`usb0`) |
| `NETWORK_UNBOUND` | TBox network not found, UDP uses the default route |
| `TBOX_SILENT` | No data from TBox for 10 s, host is probing |
| `TBOX_RESPONDING` | TBox answers again after `TBOX_SILENT` |
| `LEGACY_HOST` | Connected to a 1.x/2.x host: no network binding, no 3.x statuses |

---

## Auto-Reconnection

1. **Watchdog** monitors UDP data flow (TBox sends data every ~1 second)
2. After 10 s of silence the host reports `TBOX_SILENT`, probes TBox every 10 s and recreates
   the UDP socket after 3 unanswered probes. The bridge itself keeps running.
3. If the host process dies, clients reconnect after 1–1.5 s (random jitter);
   the first one to find no host starts a new bridge, the rest connect to it.

To stop reconnection, call `tboxClient.destroy()`.

---

## Compatibility with 1.x / 2.x

- A 3.x host serves 1.x and 2.1 apps on port 1104; each client's format is detected from its first frame.
- A 3.x client works with a 1.x/2.x host and reports `LEGACY_HOST`. On Android 10 head units
  with Wi-Fi enabled such a host cannot send commands to TBox (its socket is not bound to `usb0`).
- 1.0.x clients never reconnect after the host dies; keep the host alive.

Wire formats, handshake and detection rules: [PROTOCOL.md](PROTOCOL.md). Changes: [CHANGELOG.md](CHANGELOG.md).

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
- **Watchdog monitoring**: detects TBox unavailability within 10 seconds without stopping the bridge.
- **TBox network binding**: works on Android 10 head units with Wi-Fi enabled.
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
