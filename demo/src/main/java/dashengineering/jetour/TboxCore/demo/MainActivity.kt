package dashengineering.jetour.TboxCore.demo


import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dashing.tbox.proxy.demo.R
import dashingineering.jetour.tboxcore.types.LogType
import dashingineering.jetour.tboxcore.TBoxClient
import dashingineering.jetour.tboxcore.constants.TBoxConstants
import dashingineering.jetour.tboxcore.types.TBoxCallback
import dashingineering.jetour.tboxcore.types.TBoxClientCallback
import dashingineering.jetour.tboxcore.types.TBoxCommand
import dashingineering.jetour.tboxcore.util.ByteConverter.toLogString
import dashingineering.jetour.tboxcore.util.TBoxReceivedMessage

val command1 = TBoxCommand(
    tid = TBoxConstants.CRT_CODE,
    sid = TBoxConstants.SELF_CODE,
    cmd = 0x01,
    data = byteArrayOf(0x00, 0x00),
    textMessage = "Command 1"
)

val command2 = TBoxCommand(
    tid = TBoxConstants.MDC_CODE,
    sid = TBoxConstants.SELF_CODE,
    cmd = 0x01,
    data = byteArrayOf(0x00, 0x00),
    textMessage = "Command 2"
)

val command3 = TBoxCommand(
    tid = TBoxConstants.LOC_CODE,
    sid = TBoxConstants.SELF_CODE,
    cmd = 0x01,
    data = byteArrayOf(0x00, 0x00),
    textMessage = "Command 3"
)

val command4 = TBoxCommand(
    tid = TBoxConstants.SWD_CODE,
    sid = TBoxConstants.SELF_CODE,
    cmd = 0x01,
    data = byteArrayOf(0x00, 0x00),
    textMessage = "Command 4"
)

val command5 = TBoxCommand(
    tid = TBoxConstants.APP_CODE,
    sid = TBoxConstants.SELF_CODE,
    cmd = 0x01,
    data = byteArrayOf(0x00, 0x00),
    textMessage = "Command 5"
)

val command6 = TBoxCommand(
    tid = TBoxConstants.GATE_CODE,
    sid = TBoxConstants.SELF_CODE,
    cmd = 0x01,
    data = byteArrayOf(0x00, 0x00),
    textMessage = "Command 6"
)

val getCanFrames = TBoxCommand(
    tid = TBoxConstants.CRT_CODE,
    sid = TBoxConstants.GATE_CODE,
    cmd = 0x15,
    data = byteArrayOf(0x01, 0x02),
    textMessage = "Command getCanFrames"
)

val getSW = TBoxCommand(
    tid = TBoxConstants.CRT_CODE,
    sid = TBoxConstants.SELF_CODE,
    cmd = 0x12,
    data = byteArrayOf(0x00, 0x00, 0x01, 0x04.toByte()),
    textMessage = "Command getSW"
)

val getHW = TBoxCommand(
    tid = TBoxConstants.CRT_CODE,
    sid = TBoxConstants.SELF_CODE,
    cmd = 0x12,
    data = byteArrayOf(0x00, 0x00, 0x01, 0x05.toByte()),
    textMessage = "Command getHW"
)
val getVIN = TBoxCommand(
    tid = TBoxConstants.CRT_CODE,
    sid = TBoxConstants.SELF_CODE,
    cmd = 0x12,
    data = byteArrayOf(0x00, 0x00, 0x01, 0x0F.toByte()),
    textMessage = "Command getVIN"
)

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: PacketAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var tboxClient: TBoxClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_launcher)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PacketAdapter(recyclerView)
        recyclerView.adapter = adapter
//        val hVersion = findViewById<TextView>(R.id.hVer)
        val connectButton = findViewById<Button>(R.id.btnConnect)
        val saveLogButton = findViewById<Button>(R.id.saveLogs)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {

            // Если разрешения нет — запрашиваем (в Activity)
            ActivityCompat.requestPermissions(
                this@MainActivity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                100
            )
        }

        tboxClient = TBoxClient(
            context = applicationContext,
            callback = object : TBoxClientCallback {
                override fun onDataReceived(message: TBoxReceivedMessage) {
                    //Get received data from T-Box
                    //For to get raw ByteArray for logging data.toLogString(0)
                    adapter.addPacket(ITBoxMessage("📥", "tid: ${message.sid} sid: ${message.sid}${message.getRawData().toLogString(0)}"))
                    Log.d("QQQ", "📥 Received data: ${message.getRawData().toLogString(0)}")
                }

                override fun onLogMessage(type: LogType, tag: String, message: String) {
                    //Get internal library log messages
                    Log.d("QQQ", "📡 ${type} [${tag}] ${message}")
                    adapter.addPacket(ITBoxMessage("📡 ${type}", "[${tag}] ${message}"))
                }

                override fun onConnectionChanged(connected: Boolean) {
                    //Get connection library status
                    if (connected) {
                        connectButton.setText("Отключиться от T-BOX")
                        Handler(Looper.getMainLooper()).postDelayed({
//                            adapter.addPacket(ITBoxMessage("📡", "Отправляем запрос на получение CAN данных"))
//                            tboxClient.sendCommand(0x23.toByte(), 0x80.toByte(), 0x15, byteArrayOf(0x01, 0x02))
                            tboxClient.sendCommand(command1)
                            tboxClient.sendCommand(command2)
                            tboxClient.sendCommand(command3)
                            tboxClient.sendCommand(command4)
                            tboxClient.sendCommand(command5)
                            tboxClient.sendCommand(command6)
                            tboxClient.sendCommand(getHW)
                            tboxClient.sendCommand(getSW)
                            tboxClient.sendCommand(getVIN)
                            tboxClient.sendCommand(getCanFrames)
                        }, 3000)
                    } else {
                        connectButton.setText("Подключиться к T-BOX")
                    }
                }
            }
        )

        saveLogButton.setOnClickListener {
            adapter.saveToFile(applicationContext, )
        }


        connectButton.setOnClickListener {
            if(tboxClient.isConnected()) {
                adapter.addPacket(ITBoxMessage("📡", "Отключаемся от тбокса"))
                tboxClient.destroy()
            } else {
                adapter.addPacket(ITBoxMessage("📡", "Подключаемся к тбоксу"))
                tboxClient.initialize()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tboxClient.destroy()
    }
}