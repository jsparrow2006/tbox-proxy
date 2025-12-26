package dashengineering.jetour.TboxCore.command

sealed class TboxCommand {
    // MDC
    data class SendAT(val command: String) : TboxCommand()
    data class RebootModem(val mode: Int) : TboxCommand()
    data class ManageAPN(val apnIndex: Int, val action: Int) : TboxCommand()

    // CRT
    data object RebootTbox : TboxCommand()
    data object GetCanFrame : TboxCommand()
    data object GetCycleSignal : TboxCommand()
    data object GetHdmData : TboxCommand()
    data object GetPowerVoltages : TboxCommand()

    // LOC
    data class SubscribeLocation(val enabled: Boolean, val intervalSec: Int = 1) : TboxCommand()

    // APP/SWD
    data class SuspendApp(val module: Byte) : TboxCommand()
    data class ResumeApp(val module: Byte) : TboxCommand()
    data class StopApp(val module: Byte) : TboxCommand()
    data object PreventRestart : TboxCommand()

    // Общее
    data object GetVersions : TboxCommand()
}