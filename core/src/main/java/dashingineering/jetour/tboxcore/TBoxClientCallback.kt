package dashingineering.jetour.tboxcore

interface TBoxClientCallback {

    /**
     * Вызывается при получении данных из UDP-сокета
     * @param data сырые байты сообщения (без парсинга)
     */
    fun onDataReceived(data: ByteArray)

    /**
     * Вызывается для логирования событий библиотеки
     * @param type тип лога (DEBUG, INFO, WARN, ERROR)
     * @param tag идентификатор источника лога
     * @param message текст сообщения
     */
    fun onLogMessage(type: LogType, tag: String, message: String)

    /**
     * Опционально: изменение статуса подключения
     */
    fun onConnectionChanged(connected: Boolean) {
        // Пустая реализация по умолчанию
    }
}