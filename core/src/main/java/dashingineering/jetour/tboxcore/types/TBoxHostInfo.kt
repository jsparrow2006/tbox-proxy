package dashingineering.jetour.tboxcore.types

/** Формат TCP-обмена между клиентом и хостом моста. */
enum class TBoxWireProtocol {
    /** 1.0.x–2.0.x: `[len][payload]`, только данные. */
    LEGACY_V1,

    /** 2.1.x: `[len][type][payload]`, данные и статусы. */
    TYPED_V2,

    /** 3.x: отдельный порт, рукопожатие, информация о хосте. */
    V3
}

/**
 * Сведения о текущем хосте моста. Для хостов 1.x/2.x известен только [protocol].
 */
data class TBoxHostInfo(
    val protocol: TBoxWireProtocol,
    val hostPackage: String? = null,
    val libraryVersion: String? = null,
    /** UDP-сокет хоста привязан к сети TBox — трафик не уходит в Wi-Fi. */
    val networkBound: Boolean = false,
    val networkInterface: String? = null
) {
    val isLegacy: Boolean get() = protocol != TBoxWireProtocol.V3
}
