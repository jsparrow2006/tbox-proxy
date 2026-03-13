# TBox Proxy Library

A Kotlin/Android library that enables **safe, concurrent access to a single TBox UDP connection** from multiple applications or components. It solves the classic **"only one process can bind to a UDP port"** problem by providing a system-wide proxy service with automatic host election.

## 🎯 Problem Solved

- Only **one `DatagramSocket`** can listen on port `50047` at a time.
- Multiple apps (e.g., diagnostics, telemetry, UI) need to **send commands and receive data** from the TBox.
- If the host app crashes, another should **automatically take over** without data loss.

This library provides:
- A **single foreground service** (`TBoxBridgeService`) that owns the UDP socket.
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
    implementation("com.github.jsparrow2006:tbox-proxy:v1.0.1")
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

✅ Requires Kotlin ≥ 1.8 and AGP ≥ 8.0.

---

## 🚀 Usage

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
        override fun onDataReceived(data: ByteArray) {
            //Get received data from T-Box
            //For to get raw ByteArray for logging
            //data.toLogString(0) without lenth limit
            //data.toLogString(100) with lenth limit
        }

        override fun onLogMessage(type: LogType, tag: String, message: String) {
            //Get internal library log messages
        }

        override fun onConnectionChanged(connected: Boolean) {
            //Get connection library status
        }
    }
)
```

### 3. Connect and start receiving data

```kotlin
client.initialize()
```

🔁 On first launch (no host running), the library automatically starts its own service and connects to the TBox using the provided IP/port.
🔁 Subsequent apps simply subscribe to the existing host.

### 4. Send commands to TBox

1. send raw ByteArray command
```kotlin
client.sendRawMessage(...you-byte-array)
```

2. send parameters command
```kotlin
client.sendCommand(TBoxConstants.CRT_CODE, TBoxConstants.GATE_CODE, 0x15, byteArrayOf(0x01, 0x02))
```

2. send command with object command
```kotlin
val getCanFrames = TBoxCommand(
    tid = TBoxConstants.CRT_CODE,
    sid = TBoxConstants.GATE_CODE,
    cmd = 0x15,
    data = byteArrayOf(0x01, 0x02)
)

client.sendCommand(getCanFrames)
```

### 5. Clean up

```kotlin
override fun onDestroy() {
    tboxClient.destroy()
    super.onDestroy()
}
```

---

## 🔐 Required Permissions

### The library automatically adds to your manifest:
```kotlin
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

Ensure your app has permission to run foreground services (especially on Android 12+).

## 🧠 Key Features

- Seamless failover: if the host dies, any client can become the new host.
- Raw data delivery: receives **`ByteArray`** — you control parsing logic.
- No UI dependencies: works in services, workers, or background tasks.
- Self-contained: includes all protocol utilities (**`fillHeader`**, **`xorSum*`*, etc.).

---

Utility functions like **`fillHeader`**, **`xorSum`**, and **`extractData`** are available in **`dashingineering.jetour.tboxcore.util`**.

---

## 📄 License

MIT License. See [LICENSE](LICENSE)
