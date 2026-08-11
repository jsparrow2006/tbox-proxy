package dashingineering.jetour.tboxcore.types

enum class TBoxStatusType(val label: String) {
    UDP_BIND_FAILED("UDP bind failed"),
    UDP_BIND_SUCCESS("UDP bind success"),
    UDP_RECEIVE_ERROR("UDP receive error"),
    UDP_SEND_ERROR("UDP send error"),
    SERVICE_STARTED("Service started"),
    SERVICE_STOPPED("Service stopped"),
    SERVICE_ERROR("Service error"),
    TCP_SERVER_STARTED("TCP server started"),
    TCP_SERVER_ERROR("TCP server error")
}
