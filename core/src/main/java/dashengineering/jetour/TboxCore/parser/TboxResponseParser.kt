package dashengineering.jetour.TboxCore.parser

import dashengineering.jetour.TboxCore.models.*
import dashengineering.jetour.TboxCore.protocol.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

object TboxResponseParser {

    private val GEAR_BOX_7_DRIVE_MODES = setOf<Byte>(
        0x1B, 0x2B, 0x3B, 0x4B, 0x5B, 0x6B, 0x7B
    )

    private val GEAR_BOX_7_PREPARED_DRIVE_MODES = setOf<Byte>(
        0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70
    )

    // === MDC ===
    fun parseMDCNetState( data: ByteArray, currentTime: Long): Pair<NetState, NetValues>? {
        if (data.copyOfRange(0, 4).contentEquals(ByteArray(4) { (-1).toByte() })) return null

        var csq = 99
        var signalLevel = 0
        var regStatus = "-"
        var simStatus = "-"
        var netStatus = "-"
        var connectionChangeTime: Long? = null

        if (data.size >= 8) {
            regStatus = when (data[4].toInt()) {
                0 -> "нет"
                1 -> "домашняя сеть"
                2 -> "поиск сети"
                3 -> "регистрация отклонена"
                5 -> "роуминг"
                else -> data[4].toString()
            }
            csq = if (data[5] == 0x99.toByte()) 99 else data[5].toInt()
            signalLevel = getSignalLevel(csq)
            simStatus = when (data[6].toInt()) {
                0 -> "нет SIM"
                1 -> "SIM готова"
                2 -> "требуется PIN"
                3 -> "ошибка SIM"
                else -> data[6].toString()
            }
            netStatus = when (data[7].toInt()) {
                0 -> "-"
                2 -> "2G"
                3 -> "3G"
                4 -> "4G"
                7 -> "нет сети"
                else -> data[7].toString()
            }
        }

        connectionChangeTime = currentTime

        var imei = "-"
        var iccid = "-"
        var imsi = "-"
        var operator = "-"
        if (data.size >= 67) {
            imei = dataToString(data, 52, 15)
            iccid = dataToString(data, 8, 20)
            imsi = dataToString(data, 29, 15)
            val mcc = dataToString(data, 45, 3)
            val mnc = dataToString(data, 49, 2)
            operator = CsnOperatorResolver.getOperatorName(mcc, mnc)
        }

        return Pair(
            NetState(csq, signalLevel, netStatus, regStatus, simStatus, connectionChangeTime),
            NetValues(imei, iccid, imsi, operator)
        )
    }

    fun parseMDCAPNState( data: ByteArray, currentTime: Long): Pair<Int, APNState>? {
        if (data.copyOfRange(0, 4).contentEquals(ByteArray(4) { (-1).toByte() })) return null
        if (data.size < 103) return null

        val apnIndex = data[4].toInt() // 0 = APN1, 1 = APN2
        val apnStatus = data[6] == 0x01.toByte()
        val apnType = dataToString(data, 7, 32)
        val apnIP = dataToString(data, 39, 15)
        val apnGate = dataToString(data, 87, 15)
        val apnDNS1 = dataToString(data, 55, 15)
        val apnDNS2 = dataToString(data, 71, 15)

        return Pair(apnIndex, APNState(apnStatus, apnType, apnIP, apnGate, apnDNS1, apnDNS2, currentTime))
    }

    fun parseATResponse( data: ByteArray): String? {
        if (data.copyOfRange(0, 4).contentEquals(ByteArray(4) { (-1).toByte() })) return null
        return data.copyOfRange(10, data.size).decodeToString().trimEnd()
    }

    // === LOC ===
    fun parseLOCValues( data: ByteArray, currentTime: Long): LocValues? {
        if (data.copyOfRange(0, 4).contentEquals(ByteArray(4) { (-1).toByte() })) return null
        if (data.size < 45) return null

        val gpsData = data.copyOfRange(6, 45)
        val buffer = ByteBuffer.wrap(gpsData).order(ByteOrder.LITTLE_ENDIAN)

        val locateStatus = buffer.get().toInt() and 0xFF != 0
        val year = buffer.get().toInt() and 0xFF
        val month = buffer.get().toInt() and 0xFF
        val day = buffer.get().toInt() and 0xFF
        val hour = buffer.get().toInt() and 0xFF
        val minute = buffer.get().toInt() and 0xFF
        val second = buffer.get().toInt() and 0xFF

        buffer.position(buffer.position() + 1)
        val longitudeDir = buffer.get().toInt() and 0xFF
        val rawLongitude = buffer.int
        val longitude = rawLongitude.toDouble() / 1_000_000.0 * if (longitudeDir == 1) -1 else 1

        buffer.position(buffer.position() + 1)
        val latitudeDir = buffer.get().toInt() and 0xFF
        val rawLatitude = buffer.int
        val latitude = rawLatitude.toDouble() / 1_000_000.0 * if (latitudeDir == 1) -1 else 1

        val altitude = buffer.int.toDouble() / 1_000_000.0
        val visibleSatellites = buffer.get().toInt() and 0xFF
        val usingSatellites = buffer.get().toInt() and 0xFF
        val speed = (buffer.short.toInt() and 0xFFFF).toFloat() / 10f
        val trueDir = (buffer.short.toInt() and 0xFFFF).toFloat() / 10f
        val magneticDir = (buffer.short.toInt() and 0xFFFF).toFloat() / 10f

        return LocValues(
            rawValue = toHexString(gpsData),
            locateStatus = locateStatus,
            utcTime = UtcTime(year, month, day, hour, minute, second),
            longitude = longitude,
            latitude = latitude,
            altitude = altitude,
            visibleSatellites = visibleSatellites,
            usingSatellites = usingSatellites,
            speed = speed,
            trueDirection = trueDir,
            magneticDirection = magneticDir,
            updateTime = currentTime
        )
    }

    // === CRT ===
    fun parseCRTPowVol( data: ByteArray, currentTime: Long): VoltagesState? {
        if (!data.copyOfRange(0, 4).contentEquals(ByteArray(4) { 0 })) return null
        if (data.size < 10) return null

        val buffer = ByteBuffer.wrap(data.copyOfRange(4, 10)).order(ByteOrder.LITTLE_ENDIAN)
        val v1 = (buffer.short.toInt() and 0xFFFF).toFloat() / 1000f
        val v2 = (buffer.short.toInt() and 0xFFFF).toFloat() / 1000f
        val v3 = (buffer.short.toInt() and 0xFFFF).toFloat() / 1000f

        return VoltagesState(v1, v2, v3, currentTime)
    }

    fun parseCRTHdmData( data: ByteArray): HdmData? {
        if (!data.copyOfRange(0, 4).contentEquals(ByteArray(4) { 0 })) return null
        if (data.size < 6) return null

        val buffer = ByteBuffer.wrap(data.copyOfRange(4, 6)).order(ByteOrder.LITTLE_ENDIAN)
        val isPower = buffer.get().toInt() and 0xFF != 0
        val isIgnition = buffer.get().toInt() and 0xFF != 0
        val isCan = buffer.get().toInt() and 0xFF != 0

        return HdmData(isPower, isIgnition, isCan)
    }

    fun parseCRTCanFrame( data: ByteArray, currentTime: Long): List<CanFrameEvent> {
        if (!data.copyOfRange(0, 4).contentEquals(ByteArray(4) { 0 })) return emptyList()
        val events = mutableListOf<CanFrameEvent>()

        val rawValue = data.copyOfRange(4, data.size)
        for (i in rawValue.indices step 17) {
            if (i + 17 > rawValue.size) break
            val frame = rawValue.copyOfRange(i, i + 17)
            val canId = frame.copyOfRange(4, 8)
            if (canId.contentEquals(ByteArray(4) { 0 })) continue

            events.add(CanFrameEvent(toHexString(canId), frame.copyOfRange(9, 17)))
        }
        return events
    }

    data class CanFrameEvent(val canId: String,  val data: ByteArray)

    // === Общее ===
    fun parseVersion( data: ByteArray): String? {
        if (!data.copyOfRange(0, 4).contentEquals(ByteArray(4) { 0 })) return null
        return data.copyOfRange(4, data.size).decodeToString().trimEnd()
    }

    // === Вспомогательные ===
    private fun getSignalLevel(csq: Int): Int = when (csq) {
        in 1..10 -> 1
        in 11..16 -> 2
        in 17..24 -> 3
        in 25..32 -> 4
        else -> 0
    }

    private fun dataToString( data: ByteArray, offset: Int, length: Int): String {
        return data.decodeToString(offset, (offset + length).coerceAtMost(data.size)).trim()
    }
}