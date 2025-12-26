package dashengineering.jetour.TboxCore.models

data class NetState(
    val csq: Int = 99,
    val signalLevel: Int = 0,
    val netStatus: String = "",
    val regStatus: String = "",
    val simStatus: String = "",
    val connectionChangeTime: Long? = null
)