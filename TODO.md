# TODO

## UDP Port Already In Use — клиент не узнаёт

- [x] `UdpSocketManager` — убрана повторная попытка на том же порту
- [x] При ошибке UDP мост сообщает `UDP_BIND_FAILED`, клиент повторяет подключение через 1–1,5 с
- [x] Обычная попытка подключения логируется как INFO

## TBox не запустился — команды теряются молча

- [x] Пробный запрос при старте и каждые 10 с, пока TBox молчит
- [x] `TBOX_SILENT` → `onConnectionChanged(false)` после 10 с без данных, `TBOX_RESPONDING` при возобновлении
- [ ] Retry с exponential backoff — повтор отправки команд при отсутствии ответа (лучше на стороне приложения: только оно знает, на какую команду какой ответ)
- [x] Readiness check — `isConnected()` / `onConnectionChanged(true)` только после данных от TBox

## Прочее

- [x] `TBoxBridgeService.onDestroy()` — без `runBlocking`
- [x] `TcpClient.cleanup()` — без `runBlocking`
- [x] `FrameCodec.decode()` — ограничен реально прочитанными байтами
- [x] Разбор всех кадров из одного `read()` (`FrameReader`)
- [x] `delay(500)` при старте сервиса заменён опросом порта v3
- [x] Дублированная обёртка `TBoxCallback` убрана
- [x] `TBoxCommand.equals()/hashCode()` по содержимому
- [x] Storage permissions перенесены из библиотеки в demo
- [x] Переподключение при обрыве TCP
- [x] Unit-тесты для `FrameCodec`, протокола и моста
- [ ] Unit-тесты для `ByteConverter`, `TBoxReceivedMessage`
- [ ] `TBoxReceivedMessage`: `tid` берётся из байта 9, `sid` — из байта 8 (в `fillHeader` наоборот). Не менялось ради совместимости, стоит проверить
