package dashengineering.jetour.TboxCore.models

data class APNState(
    val apnStatus: Boolean? = null,
    val apnType: String = "",
    val apnIP: String = "",
    val apnGate: String = "",
    val apnDNS1: String = "",
    val apnDNS2: String = "",
    val changeTime: Long? = null
)