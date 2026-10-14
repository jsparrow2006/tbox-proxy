package dashingineering.jetour.tboxcore.types

/**
 * По проводу передаётся `ordinal`, поэтому новые значения добавляются только в конец.
 * Клиенты 2.1.x знают значения до [LOG] включительно.
 */
enum class TBoxStatusType(val label: String) {
    UDP_BIND_FAILED("UDP bind failed"),
    UDP_BIND_SUCCESS("UDP bind success"),
    UDP_RECEIVE_ERROR("UDP receive error"),
    UDP_SEND_ERROR("UDP send error"),
    SERVICE_STARTED("Service started"),
    SERVICE_STOPPED("Service stopped"),
    SERVICE_ERROR("Service error"),
    TCP_SERVER_STARTED("TCP server started"),
    TCP_SERVER_ERROR("TCP server error"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    DISCONNECTED("Disconnected"),
    LOG("LOG"),

    /** Хост привязал UDP-сокет к сети TBox (например, `usb0`). */
    NETWORK_BOUND("Network bound"),

    /** Сеть TBox не найдена — UDP идёт по маршруту по умолчанию. */
    NETWORK_UNBOUND("Network unbound"),

    /** TBox молчит дольше порога; хост шлёт пробные запросы и пересоздаёт сокет. */
    TBOX_SILENT("TBox silent"),

    /** TBox снова отвечает после [TBOX_SILENT]. */
    TBOX_RESPONDING("TBox responding"),

    /** Клиент подключён к хосту старой версии (1.x/2.x): без привязки к сети и без статусов v3. */
    LEGACY_HOST("Legacy host");

    companion object {
        /** Последний статус, который понимают клиенты 2.1.x. */
        val LAST_V2_STATUS: TBoxStatusType = LOG
    }
}
