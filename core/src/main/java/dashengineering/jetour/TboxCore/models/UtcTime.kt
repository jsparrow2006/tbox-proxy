package dashengineering.jetour.TboxCore.models

data class UtcTime(
    val year: Int = 0,
    val month: Int = 0,
    val day: Int = 0,
    val hour: Int = 0,
    val minute: Int = 0,
    val second: Int = 0
) {
    fun formatDateTime(): String {
        return "%02d.%02d.%02d %02d:%02d:%02d".format(
            day, month, year, hour, minute, second
        )
    }
}