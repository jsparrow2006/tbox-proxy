# TODO

## UDP Port Already In Use — клиент не узнаёт

**Проблема:** Когда UDP-порт (11048) уже занят, `UdpSocketManager.initialize()` возвращает `false`, сервис вызывает `stopSelf()` и молча умирает. Клиентский callback `onConnectionChanged` не вызывается.

**Что нужно пофиксить:**
- [ ] `UdpSocketManager.initialize()` — убрать повторную попытку на том же порту (строка 41), она гарантированно падает
- [ ] `TBoxBridgeService.startBridge()` — при ошибке UDP уведомить клиентов (через error-frame в TCP или через сохранённый статус)
- [ ] `TBoxClient.startAsServer()` — вызывать `callback.onConnectionChanged(false)` при ошибке `tcpClient.connect()`
- [ ] Лог на строке 33 `UdpSocketManager` — изменить с `ERROR` на `INFO` (это обычная попытка подключения, не ошибка)

## TBox не запустился — команды теряются молча

**Проблема:** Бридж стартует успешно (биндит локальный порт), receive loop ждёт данные от TBox. Если TBox не запустился, `receive()` каждую секунду таймаутит. Клиент отправляет команды через `send()` — UDP fire-and-forget, пакеты уходят в пустоту. `send()` возвращает `true`. Клиент не получает ни одного ответа, ни одного callback'а об ошибке.

**Что нужно добавить:**
- [ ] Keep-alive / heartbeat — периодический ping чтобы понять жив ли TBox
- [ ] Connection timeout — если за N секунд receive loop не получил ни одного пакета от TBox → `onConnectionChanged(false)`
- [ ] Retry с exponential backoff — повтор отправки команд при отсутствии ответа
- [ ] Readiness check — перед отправкой команд убедиться что TBox отвечает

## Прочее

- [ ] `TBoxBridgeService.onDestroy()` — `runBlocking` на main thread, ANR risk
- [ ] `TcpClient.cleanup()` — `runBlocking` внутри suspend-контекста, deadlock risk
- [ ] `FrameCodec.decode()` — проверяет `buffer.size` вместо реального количества прочитанных байтов, corruption при partial reads
- [ ] `ClientHandler.receive()` / `TcpClient.receiveLoop()` — при нескольких фреймах в одном read возвращается только первый, остальные ждут следующего read
- [ ] `TBoxClient.startAsServer()` — `delay(500)` magic number вместо ожидания реального события готовности сервиса
- [ ] Дублированный `TBoxCallback` обёртка в `connectAsClient()` и `startAsServer()`
- [ ] `TBoxCommand` — data class с `ByteArray`, `equals()/hashCode()` по ссылке
- [ ] Storage permissions (`WRITE/READ_EXTERNAL_STORAGE`) в library manifest — лишние для потребителей библиотеки
- [ ] Нет reconnect логики при обрыве TCP-соединения
- [ ] Нет unit-тестов для `FrameCodec`, `ByteConverter`, `TBoxReceivedMessage`
