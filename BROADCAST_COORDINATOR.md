# TBox Broadcast Coordinator

## Обзор

Механизм межприложенного взаимодействия для координации нескольких приложений, работающих с TBox через UDP.

## Проблема

UDP-сокет может быть создан только в одном процессе. Если запущено несколько приложений с библиотекой, только одно может владеть сокетом.

## Решение

**Broadcast Coordinator** позволяет приложениям "видеть" друг друга:
1. При запуске приложение отправляет **PING**
2. Если хост уже запущен — он отвечает **PONG** со своим packageName
3. Если ответа нет — приложение становится хостом и создаёт UDP-сокет
4. Все приложения получают данные через **Broadcast**

## Компоненты

### TboxBroadcastCoordinator
Базовый класс для координации через Broadcast.

### TboxBroadcastHostService
Сервис для хоста, который владеет UDP-сокетом.

### TboxBroadcastClient
Клиент для подключения к TBox с автоматическим выбором хоста.

## Использование

### Подключение

```kotlin
val client = TboxBroadcastClient(context)

client.onEvent = { event ->
    when (event) {
        is TboxBroadcastClient.Event.HostConnected -> { /* ... */ }
        is TboxBroadcastClient.Event.HostFound -> { /* Нашли хост */ }
        is TboxBroadcastClient.Event.NoHostFound -> { /* Становимся хостом */ }
        is TboxBroadcastClient.Event.DataReceived -> { /* Данные от TBox */ }
        is TboxBroadcastClient.Event.LogMessage -> { /* Лог */ }
    }
}

client.connect()
```

### Отправка команд

```kotlin
// Готовый пакет
client.sendCommand(byteArrayOf(0x8E, 0x5D, ...))

// Или с конструктором пакетов
client.sendCommand(tid = 0x01, sid = 0x01, cmd = 0x10, data = byteArrayOf())
```

### Отключение

```kotlin
client.disconnect()
```

## Протокол Broadcast

| Action | Описание | Данные |
|--------|----------|--------|
| PING | Поиск хоста | packageName отправителя |
| PONG | Ответ хоста | packageName хоста |
| COMMAND | Команда на отправку | ByteArray с данными |
| DATA | Данные от TBox | ByteArray с данными |
| LOG | Лог сообщение | level, tag, message |
| HOST_CONNECTED | Хост подключён | - |
| HOST_DISCONNECTED | Хост отключён | - |

## Пример работы

### Сценарий 1: Первое приложение
1. Запуск приложения A
2. Отправка PING
3. Ответа нет → становимся хостом
4. Запуск `TboxBroadcastHostService`
5. Создание UDP-сокета

### Сценарий 2: Второе приложение
1. Запуск приложения B (A уже работает)
2. Отправка PING
3. Получен PONG от A
4. Подключение к существующему хосту
5. Получение данных через Broadcast

## Преимущества

- ✅ Несколько приложений могут работать с одним TBox
- ✅ Автоматический выбор хоста
- ✅ Отказоустойчивость (если хост упал — другой станет хостом)
- ✅ Общие логи и данные между приложениями

## Ограничения

- ⚠️ Broadcast не гарантируют доставку (могут теряться при высокой нагрузке)
- ⚠️ Данные передаются в открытом виде (любой app может читать)
- ⚠️ Android 13+ может ограничивать фоновые Broadcast

## Альтернативы

Для продакшена рассмотрите:
- **AIDL** (строгая типизация, гарантия доставки)
- **ContentProvider** (доступ из разных приложений)
- **Network Socket** (localhost TCP/UDP)
