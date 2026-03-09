package dashengineering.jetour.TboxCore.demo


import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dashing.tbox.proxy.demo.R
import dashingineering.jetour.tboxcore.client.TboxProxyClient
import dashingineering.jetour.tboxcore.util.buildTboxPacket

class MainActivity : AppCompatActivity() {
    private lateinit var client: TboxProxyClient
    private lateinit var adapter: PacketAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var tboxClient: TboxProxyClient
    private var isConected: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_launcher)

        tboxClient = TboxProxyClient(this)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PacketAdapter(recyclerView)
        recyclerView.adapter = adapter
        val hVersion = findViewById<TextView>(R.id.hVer)
        val connectButton = findViewById<Button>(R.id.btnConnect)

        tboxClient.onEvent = { event ->
            when (event) {
                is TboxProxyClient.Event.HostConnected -> {
                    adapter.addPacket(ITBoxMessage("📡", "Подключились к хосту"))
                    val hostname = tboxClient.getHostPackageName()
                    hVersion.text = hostname
                    isConected = true;
                    connectButton.setText("Отключиться от T-BOX")
                    //Запрос на получение кан данных
                    tboxClient.sendCommand(buildTboxPacket(0x23.toByte(), 0x80.toByte(), 0x15, byteArrayOf(0x01, 0x02)))
                }
                is TboxProxyClient.Event.DataReceived -> {
                    adapter.addPacket(ITBoxMessage("📥", event.data.toString()))
                }
                is TboxProxyClient.Event.LogMessage -> {
                    adapter.addPacket(ITBoxMessage("📡", "[${event.tag}] ${event.message}"))
                    Log.d("TBoxLog", "[${event.tag}] ${event.message}")
                }
                is TboxProxyClient.Event.HostDied -> {
                    Log.w("TBox", "Хост умер — клиент сам станет хостом")
                    adapter.addPacket(ITBoxMessage("📡", "Хост умер — клиент сам станет хостом"))
                }

                TboxProxyClient.Event.HostDisconnected -> {
                    connectButton.setText("Подключиться к T-BOX")
                    isConected = false
                }
            }
        }

        connectButton.setOnClickListener {
            if(isConected) {
                adapter.addPacket(ITBoxMessage("📡", "Отключаемся от хоста"))
                tboxClient.disconnect()
            } else {
                adapter.addPacket(ITBoxMessage("📡", "Подключаемся к хосту"))
                tboxClient.connect()
            }
        }

//        saveButton.setOnClickListener {
//            adapter.saveToFile(this)
//        }

//        client.connect()

//        Handler(Looper.getMainLooper()).postDelayed({
//            client.subscribeToCanRaw(1,1)
//            client.subscribeToCanRaw(1,2)
//            client.subscribeToCanRaw(4,1)
//        }, 3000)
    }

    override fun onDestroy() {
        super.onDestroy()
        tboxClient.disconnect()
    }
}