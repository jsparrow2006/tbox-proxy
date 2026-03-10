package dashengineering.jetour.TboxCore.demo

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dashing.tbox.proxy.demo.R
import dashingineering.jetour.tboxcore.client.TboxBroadcastClient

/**
 * Пример использования TboxBroadcastClient с межприложенным взаимодействием через Broadcast
 */
class BroadcastActivity : AppCompatActivity() {
    private lateinit var client: TboxBroadcastClient
    private lateinit var adapter: PacketAdapter
    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_launcher)

        client = TboxBroadcastClient(this)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PacketAdapter(recyclerView)
        recyclerView.adapter = adapter
        
        val hVersion = findViewById<TextView>(R.id.hVer)
        val connectButton = findViewById<Button>(R.id.btnConnect)

        client.onEvent = { event ->
            when (event) {
                is TboxBroadcastClient.Event.HostConnected -> {
                    adapter.addPacket(ITBoxMessage("✅", "Подключились к хосту"))
                    hVersion.text = if (client.isHost()) "Я хост" else "Клиент"
                }
                is TboxBroadcastClient.Event.HostFound -> {
                    adapter.addPacket(ITBoxMessage("🔍", "Хост найден: ${event.packageName}"))
                    Log.d("TBoxBroadcast", "Хост найден: ${event.packageName}")
                }
                is TboxBroadcastClient.Event.NoHostFound -> {
                    adapter.addPacket(ITBoxMessage("📡", "Хост не найден, становимся хостом"))
                    Log.d("TBoxBroadcast", "Хост не найден, становимся хостом")
                }
                is TboxBroadcastClient.Event.DataReceived -> {
                    adapter.addPacket(ITBoxMessage("📥", "Данные: ${event.data.size} байт"))
                    Log.d("TBoxBroadcast", "Получены данные: ${event.data.size} байт")
                }
                is TboxBroadcastClient.Event.LogMessage -> {
                    adapter.addPacket(ITBoxMessage("📝", "[${event.tag}] ${event.message}"))
                    Log.d("TBoxLog", "[${event.tag}] ${event.message}")
                }
                is TboxBroadcastClient.Event.HostDisconnected -> {
                    adapter.addPacket(ITBoxMessage("❌", "Хост отключён"))
                }
            }
        }

        connectButton.setOnClickListener {
            adapter.addPacket(ITBoxMessage("🔌", "Подключение..."))
            client.connect()
        }

        // Пример отправки команды через 3 секунды после подключения
        /*
        Handler(Looper.getMainLooper()).postDelayed({
            // Запрос версии: tid=0x01, sid=0x01, cmd=0x10
            client.sendCommand(tid = 0x01, sid = 0x01, cmd = 0x10)
        }, 3000)
        */
    }

    override fun onDestroy() {
        super.onDestroy()
        client.disconnect()
    }
}
