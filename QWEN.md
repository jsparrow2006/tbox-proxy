# TBox Proxy Library — Project Context

## Project Overview

**TBox Proxy Library** — это Kotlin/Android библиотека, которая обеспечивает **безопасный конкурентный доступ к единому UDP-соединению TBox** из нескольких приложений или компонентов. Она решает классическую проблему **"только один процесс может привязаться к UDP-порту"**, предоставляя прокси-сервис уровня системы с автоматическим выбором хоста.

### Архитектура проекта

```
Tboxproxylib/
├── core/           # Библиотека (Android Library)
│   └── src/main/
│       ├── aidl/   # AIDL интерфейсы для IPC
│       ├── java/   # Основная реализация
│       └── AndroidManifest.xml
├── demo/           # Демонстрационное приложение
│   └── src/main/
│       ├── java/   # Demo app code
│       ├── res/    # Resources
│       └── AndroidManifest.xml
├── build.gradle.kts        # Root build configuration
├── settings.gradle.kts     # Project settings
└── gradle.properties       # Gradle settings
```

### Ключевые компоненты

| Компонент | Описание |
|-----------|----------|
| `TboxHostService` | Foreground-сервис, владеющий UDP-сокетом |
| `TboxProxyClient` | Клиент для подключения к хосту или становления хостом |
| `TboxUdpHost` | Управление UDP-соединением (отправка/получение) |
| `ITboxHostService.aidl` | AIDL интерфейс для IPC между процессами |
| `ITboxHostCallback.aidl` | AIDL callback интерфейс для событий |

### Технологии

- **Kotlin** 1.9.0
- **Android Gradle Plugin** 8.6.0
- **Compile SDK** 34 (extension 11)
- **Min SDK** 24
- **Java Compatibility** 17
- **Coroutines** для асинхронных операций
- **AIDL** для межпроцессного взаимодействия

---

## Building and Running

### Сборка проекта

```bash
# Сборка всех модулей
./gradlew build

# Сборка debug-версии
./gradlew assembleDebug

# Сборка release-версии
./gradlew assembleRelease

# Сборка только core-модуля
./gradlew :core:build

# Сборка только demo-модуля
./gradlew :demo:build
```

### Запуск демонстрационного приложения

```bash
# Установка на подключенное устройство
./gradlew :demo:installDebug

# Запуск с очисткой
./gradlew :demo:clean :demo:installDebug
```

### Тестирование

```bash
# Запуск всех тестов
./gradlew test

# Тесты core-модуля
./gradlew :core:test

# Тесты demo-модуля
./gradlew :demo:test

# Android instrumented тесты
./gradlew connectedAndroidTest
```

### Публикация (JitPack)

Библиотека публикуется через JitPack. Конфигурация в `jitpack.yml`:
```yaml
jdk:
  - openjdk17
```

---

## Development Conventions

### Структура пакетов

**core:**
- `dashingineering.jetour.tboxcore.client` — клиентские классы
- `dashingineering.jetour.tboxcore.service` — сервисы
- `dashingineering.jetour.tboxcore.constants` — константы
- `dashingineering.jetour.tboxcore.util` — утилиты

### Стиль кода

- **Kotlin code style:** official (`kotlin.code.style=official`)
- **JVM target:** 17
- **AndroidX:** включено
- **Non-transitive R class:** включено

### Основные практики

1. **Coroutines:** Используется `CoroutineScope` с `Dispatchers.IO` для сетевых операций
2. **AIDL:** Все IPC вызовы через AIDL интерфейсы
3. **Foreground Service:** `TboxHostService` работает как foreground-сервис с уведомлением
4. **Thread Safety:** Использование `Mutex` для защиты общих ресурсов

### Зависимости (core)

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
implementation("androidx.annotation:annotation:1.7.1")
implementation("androidx.core:core-ktx:1.12.0")
```

### Зависимости (demo)

```kotlin
implementation("androidx.core:core-ktx:1.10.0")
implementation(libs.androidx.appcompat)
implementation(libs.material)
implementation("androidx.activity:activity:1.7.0")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
implementation(project(":core"))
```

---

## Key Features

### Автоматический выбор хоста
- При запуске клиент ищет существующий хост через Intent с action `dashingineering.jetour.tboxcore.HOST_SERVICE`
- Если хост найден — подключается к нему
- Если хоста нет — запускает собственный `TboxHostService`

### Отказоустойчивость
- При падении хоста любой клиент может стать новым хостом
- Автоматическая повторная попытка подключения через случайный интервал (200-800ms)

### Raw data delivery
- Получение данных в виде `ByteArray`
- Утилиты для парсинга: `fillHeader()`, `xorSum()`, `checkPacket()`, `extractData()`

### TBox Protocol
- **IP по умолчанию:** `192.168.225.1`
- **Порт по умолчанию:** `50047`
- **Заголовок пакета:** 13 байт (magic bytes `0x8E 0x5D`, длина, TID, SID, параметры)
- **Контрольная сумма:** XOR sum всех байт после заголовка

---

## Usage Example

```kotlin
// Создание клиента
val client = TboxProxyClient(
    context = this,
    defaultIp = "192.168.225.1",
    defaultPort = 50047
)

// Обработчики событий
client.onEvent = { event ->
    when (event) {
        is TboxProxyClient.Event.HostConnected -> { /* ... */ }
        is TboxProxyClient.Event.DataReceived -> { /* ... */ }
        is TboxProxyClient.Event.HostDied -> { /* ... */ }
    }
}

// Подключение
client.connect()

// Отправка команды
client.sendCommand(buildCommand())

// Очистка
client.disconnect()
```

---

## Required Permissions

Библиотека автоматически добавляет:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" /> <!-- Android 13+ -->
```

---

## File Reference

| Файл | Описание |
|------|----------|
| `core/build.gradle.kts` | Конфигурация core-модуля |
| `demo/build.gradle.kts` | Конфигурация demo-приложения |
| `core/src/main/aidl/.../ITboxHostService.aidl` | AIDL интерфейс сервиса |
| `core/src/main/aidl/.../ITboxHostCallback.aidl` | AIDL callback интерфейс |
| `core/src/main/java/.../TboxHostService.kt` | Реализация foreground-сервиса |
| `core/src/main/java/.../TboxProxyClient.kt` | Клиент для подключения к хосту |
| `core/src/main/java/.../TboxUdpHost.kt` | Управление UDP-соединением |
| `core/src/main/java/.../UdpUtils.kt` | Утилиты для работы с протоколом |
| `core/src/main/java/.../TboxConstants.kt` | Константы (IP, порт) |
