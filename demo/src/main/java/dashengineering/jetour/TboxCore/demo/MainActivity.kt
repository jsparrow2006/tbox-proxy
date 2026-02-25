package dashengineering.jetour.TboxCore.demo

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.dashing.tbox.proxy.demo.R
import dashingineering.jetour.tboxcore.client.TboxProxyClient
import dashingineering.jetour.tboxcore.util.fillHeader
import dashingineering.jetour.tboxcore.util.xorSum

class MainActivity : AppCompatActivity() {
    private lateinit var tboxClient: TboxProxyClient

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        tboxClient = TboxProxyClient(this);
        tboxClient.onEvent = { event ->
            when (event) {
                is TboxProxyClient.Event.HostConnected -> {
                    Log.i("TBox", "Подключились к хосту")
                    // Можно отправить команду
                    sendVersionRequest()
                    tboxClient.getHostPackageName()
                }
                is TboxProxyClient.Event.DataReceived -> {
                    // Получили сырые данные от TBox
                    handleRawData(event.data)
                }
                is TboxProxyClient.Event.LogMessage -> {
                    Log.d("TBoxLog", "[${event.tag}] ${event.message}")
                }
                is TboxProxyClient.Event.HostDied -> {
                    Log.w("TBox", "Хост умер — клиент сам станет хостом")
                }

                TboxProxyClient.Event.HostDisconnected -> TODO()
            }
        }
        tboxClient.connect()
    }

    override fun onDestroy() {
        tboxClient.disconnect()
        super.onDestroy()
    }

    private fun sendVersionRequest() {
        val cmd = buildCommand(
            tid = 0x25, // MDC_CODE
            sid = 0x50, // SELF_CODE
            param = 0x01, // get version
            payload = byteArrayOf(0x00, 0x00)
        )
        tboxClient.sendCommand(cmd)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun handleRawData(data: ByteArray) {
        // Парси как хочешь!
        // Например, определи TID и CMD:
        if (data.size >= 14) {
            val tid = data[9]
            val cmd = data[12]
            Log.d("TBox", "TID=0x${tid.toHexString()}, CMD=0x${cmd.toHexString()}")
            // Дальше — твой парсер
        }
    }

    private fun buildCommand(tid: Int, sid: Int, param: Int, payload: ByteArray): ByteArray {
        val header = fillHeader(payload.size, tid.toByte(), sid.toByte(), param.toByte())
        val withPayload = header + payload
        val checksum = xorSum(withPayload)
        return withPayload + checksum
    }
}