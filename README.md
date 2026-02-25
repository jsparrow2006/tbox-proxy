# TBox Proxy Library

A Kotlin/Android library that enables **safe, concurrent access to a single TBox UDP connection** from multiple applications or components. It solves the classic **"only one process can bind to a UDP port"** problem by providing a system-wide proxy service with automatic host election.

## 🎯 Problem Solved

- Only **one `DatagramSocket`** can listen on port `50047` at a time.
- Multiple apps (e.g., diagnostics, telemetry, UI) need to **send commands and receive data** from the TBox.
- If the host app crashes, another should **automatically take over** without data loss.

This library provides:
- A **single foreground service** (`TboxHostService`) that owns the UDP socket.
- An **AIDL interface** for inter-process communication (IPC).
- **Automatic host election**: any client can become the host if none exists.
- **Zero boilerplate**: no need to write your own service or manage sockets.

---

## 📦 Installation

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
    implementation("com.github.jsparrow2006:tbox-proxy:1.0.0")
}
```

✅ Requires Kotlin ≥ 1.8 and AGP ≥ 8.0.

## 🚀 Usage

### 1. Create a client instance

```kotlin
val client = TboxProxyClient(
    context = this,
    defaultIp = "192.168.225.1", // optional
    defaultPort = 50047           // optional
)
```

### 2. Set up event handlers

```kotlin
client.onEvent = { event ->
    when (event) {
        is TboxProxyClient.Event.HostConnected -> {
            Log.i("TBox", "Connected to host")
        }
        is TboxProxyClient.Event.DataReceived -> {
            // Handle raw TBox response (ByteArray)
            parseTboxData(event.data)
        }
        is TboxProxyClient.Event.LogMessage -> {
            Log.d("TBoxLog", "[${event.tag}] ${event.message}")
        }
        is TboxProxyClient.Event.HostDied -> {
            // Host crashed — client will auto-attempt to become new host
            Log.w("TBox", "Host died, attempting takeover...")
        }
    }
}
```

### 3. Connect and start receiving data

```kotlin
client.connect()
```

🔁 On first launch (no host running), the library automatically starts its own TboxHostService and connects to the TBox using the provided IP/port.
🔁 Subsequent apps simply subscribe to the existing host.

### 4. Send commands to TBox

```kotlin
val command = buildCommand(...) // your custom command builder
client.sendCommand(command)
```

### 5. Clean up

```kotlin
override fun onDestroy() {
    client.disconnect()
    super.onDestroy()
}
```

## 🔐 Required Permissions

### The library automatically adds to your manifest:
```kotlin
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" /> <!-- Android 13+ -->
```

Ensure your app has permission to run foreground services (especially on Android 12+).

## 🧠 Key Features

- Automatic host discovery: uses **Intent** with action **dashingineering.jetour.tboxcore.HOST_SERVICE**.
- Seamless failover: if the host dies, any client can become the new host.
- Raw data delivery: receives **ByteArray** — you control parsing logic.
- No UI dependencies: works in services, workers, or background tasks.
- Self-contained: includes all protocol utilities (**fillHeader**, **xorSum**, etc.).

## 🛠 Example: Building a Command

```kotlin
fun buildVersionRequest(): ByteArray {
    val tid = 0x25 // MDC_CODE
    val sid = 0x50 // SELF_CODE
    val cmd = 0x01 // get version
    val payload = byteArrayOf(0x00, 0x00)

    val header = fillHeader(payload.size, tid.toByte(), sid.toByte(), cmd.toByte())
    val withPayload = header + payload
    val checksum = xorSum(withPayload)
    return withPayload + checksum
}
```

Utility functions like **fillHeader**, **xorSum**, and **extractData** are available in **dashingineering.jetour.tboxcore.util**.

## 📄 License

MIT License. See [LICENSE](LICENSE)
