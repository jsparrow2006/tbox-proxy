package dashengineering.jetour.TboxCore.demo


import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dashing.tbox.proxy.demo.R
import dashingineering.jetour.tboxcore.LogType
import dashingineering.jetour.tboxcore.TBoxClient
import dashingineering.jetour.tboxcore.TBoxClientCallback

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
        val hVersion = findViewById<TextView>(R.id.hVer)
        val connectButton = findViewById<Button>(R.id.btnConnect)

        tboxClient = TBoxClient(
            context = applicationContext,
            callback = object : TBoxClientCallback {
                override fun onDataReceived(data: ByteArray) {
                    adapter.addPacket(ITBoxMessage("📥", data.toString()))
                }

                override fun onLogMessage(type: LogType, tag: String, message: String) {
                    adapter.addPacket(ITBoxMessage("📡 ${type}", "[${tag}] ${message}"))
                }

                override fun onConnectionChanged(connected: Boolean) {
                    if (connected) {
                        connectButton.setText("Отключиться от T-BOX")
                        Handler(Looper.getMainLooper()).postDelayed({
                            adapter.addPacket(ITBoxMessage("📡", "Отправляем запрос на получение CAN данных"))
                            tboxClient.sendCommand(0x23.toByte(), 0x80.toByte(), 0x15, byteArrayOf(0x01, 0x02))
                        }, 3000)
                    } else {
                        connectButton.setText("Подключиться к T-BOX")
                    }
                }
            }
        )

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
    }
}