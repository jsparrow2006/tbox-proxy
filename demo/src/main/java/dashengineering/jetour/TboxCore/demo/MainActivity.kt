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

class MainActivity : AppCompatActivity() {
    private lateinit var client: TboxProxyClient
    private lateinit var adapter: PacketAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var tboxClient: TboxProxyClient

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
                    // Можно отправить команду
//                    sendVersionRequest()
                    val hostname = tboxClient.getHostPackageName()
                    hVersion.text = hostname
                }
                is TboxProxyClient.Event.DataReceived -> {
                    // Получили сырые данные от TBox
//                    handleRawData(event.data)
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

                TboxProxyClient.Event.HostDisconnected -> TODO()
            }
        }

//            val client = TBoxClient("192.168.225.1", 60000, object : TBoxClient.OnMessage {
////            val client = TBoxClient("192.168.225.1", 60001, object : TBoxClient.OnMessage {
//
//                override fun onVersionReceived(hVer: String, sVer: String) {
//                    runOnUiThread {
//                        sVersion.text = sVer
//                        hVersion.text = hVer
//                    }
//                }
//
//                override fun onRAWPacketReceived(packetInfo: ITBoxMessage) {
//                    runOnUiThread {
//                        adapter.addPacket(packetInfo)
//                    }
//                }
//
//                override fun onCanPacketReceived(packetInfo: ITBoxMessage) {
//                    runOnUiThread {
//                        adapter.addPacket(packetInfo)
//                    }
//                }
//
//                override fun onError(packetInfo: ITBoxMessage) {
//                    runOnUiThread {
//                        adapter.addPacket(packetInfo)
//                    }
//                }
//
//                override fun onConnect(isConnect: Boolean) {
//                    Log.d("TBoxClient", "Is Connected $isConnect")
//                    if (isConnect) {
//                        connectButton.text = "Отключиться от TBox"
//                        connectButton.setBackgroundColor(Color.RED)
//                    } else {
//                        connectButton.text = "Подключиться к TBox"
//                        connectButton.setBackgroundColor(Color.rgb(108, 92, 172))
////                        adapter.clear()
//                    }
//                }
//            })

        connectButton.setOnClickListener {
            adapter.addPacket(ITBoxMessage("📡", "Подключаемся к хосту"))
            tboxClient.connect()
//            if(client.isConnected()) {
//                client.disconnect()
//            } else {
//                client.connect()
//            }
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
//        client.disconnect()
    }
}