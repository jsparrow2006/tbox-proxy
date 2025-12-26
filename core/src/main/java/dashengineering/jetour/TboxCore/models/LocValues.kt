package dashengineering.jetour.TboxCore.models

data class LocValues(
    val rawValue: String = "",
    val locateStatus: Boolean = false,
    val utcTime: UtcTime? = null,
    val longitude: Double = 0.0,
    val latitude: Double = 0.0,
    val altitude: Double = 0.0,
    val visibleSatellites: Int = 0,
    val usingSatellites: Int = 0,
    val speed: Float = 0f,
    val trueDirection: Float = 0f,
    val magneticDirection: Float = 0f,
    val updateTime: Long? = null // ← заменено
)