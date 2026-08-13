package dashengineering.jetour.TboxCore.demo


import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dashing.tbox.proxy.demo.R
import dashingineering.jetour.tboxcore.types.LogType
import dashingineering.jetour.tboxcore.types.TBoxStatus
import dashingineering.jetour.tboxcore.types.TBoxStatusType
import dashingineering.jetour.tboxcore.TBoxClient
import dashingineering.jetour.tboxcore.constants.TBoxConstants
import dashingineering.jetour.tboxcore.types.TBoxClientCallback
import dashingineering.jetour.tboxcore.types.TBoxCommand
import dashingineering.jetour.tboxcore.util.ByteConverter.toLogString
import dashingineering.jetour.tboxcore.util.TBoxReceivedMessage

val getCanFrames = TBoxCommand(
    tid = TBoxConstants.CRT_CODE,
    sid = TBoxConstants.GATE_CODE,
    cmd = 0x15,
    data = byteArrayOf(0x01, 0x02),
    textMessage = "Command getCanFrames"
)

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: PacketAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var tboxClient: TBoxClient
    private lateinit var tvStatus: TextView
    private lateinit var connectButton: Button
    private lateinit var connectProgress: ProgressBar
    private lateinit var canButton: Button
    private lateinit var btnScrollBottom: Button

    private var isConnecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_launcher)

        tvStatus = findViewById(R.id.tvStatus)
        connectButton = findViewById(R.id.btnConnect)
        connectProgress = findViewById(R.id.connectProgress)
        canButton = findViewById(R.id.btnSubscribeCan)
        btnScrollBottom = findViewById(R.id.btnScrollBottom)
        val saveLogButton = findViewById<Button>(R.id.saveLogs)
        val clearLogButton = findViewById<Button>(R.id.clearLogs)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PacketAdapter(recyclerView) { isAtBottom ->
            runOnUiThread {
                btnScrollBottom.visibility = if (isAtBottom) View.GONE else View.VISIBLE
            }
        }
        recyclerView.adapter = adapter

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
        }

        tboxClient = TBoxClient(
            context = applicationContext,
            callback = object : TBoxClientCallback {
                override fun onDataReceived(message: TBoxReceivedMessage) {
                    adapter.addPacket(ITBoxMessage("DATA", message.getRawData().toLogString(0)))
                }

                override fun onLogMessage(type: LogType, tag: String, message: String) {
                    adapter.addPacket(ITBoxMessage("${type.label}", "[$tag] $message"))
                }

                override fun onConnectionChanged(connected: Boolean) {
                    if (connected) {
                        isConnecting = false
                        setButtonState(ButtonState.CONNECTED)
                        adapter.addPacket(ITBoxMessage("CONN", "Подключено (${tboxClient.getMode()})"))
                    } else {
                        isConnecting = false
                        setButtonState(ButtonState.IDLE)
                        adapter.addPacket(ITBoxMessage("CONN", "Отключено"))
                    }
                }

                override fun onStatusChanged(status: TBoxStatus) {
                    adapter.addPacket(ITBoxMessage("STATUS", "${status.type.label}: ${status.message}"))
                    updateStatusBar(status)
                    when (status.type) {
                        TBoxStatusType.CONNECTING -> {
                            isConnecting = true
                            setButtonState(ButtonState.CONNECTING)
                        }
                        TBoxStatusType.CONNECTED -> {
                            isConnecting = false
                            setButtonState(ButtonState.CONNECTED)
                        }
                        TBoxStatusType.DISCONNECTED -> {
                            isConnecting = false
                            setButtonState(ButtonState.IDLE)
                        }
                        else -> {}
                    }
                }
            }
        )

        connectButton.setOnClickListener {
            when {
                tboxClient.isConnected() -> {
                    adapter.addPacket(ITBoxMessage("CMD", "Отключаемся от тбокса"))
                    tboxClient.destroy()
                    isConnecting = false
                    setButtonState(ButtonState.IDLE)
                    updateStatusText("Idle", "#9E9E9E")
                }
                isConnecting -> {
                    adapter.addPacket(ITBoxMessage("CMD", "Отмена подключения"))
                    tboxClient.destroy()
                    isConnecting = false
                    setButtonState(ButtonState.IDLE)
                    updateStatusText("Cancelled", "#FF9800")
                }
                else -> {
                    adapter.addPacket(ITBoxMessage("CMD", "Подключаемся к тбоксу..."))
                    isConnecting = true
                    setButtonState(ButtonState.CONNECTING)
                    updateStatusText("Connecting...", "#FF9800")
                    tboxClient.initialize()
                }
            }
        }

        canButton.setOnClickListener {
            if (tboxClient.isConnected()) {
                tboxClient.sendCommand(getCanFrames)
            }
        }

        saveLogButton.setOnClickListener {
            adapter.saveToFile(applicationContext)
        }

        clearLogButton.setOnClickListener {
            adapter.clear()
            adapter.notifyDataSetChanged()
        }

        btnScrollBottom.setOnClickListener {
            adapter.scrollToBottom()
        }
    }

    private enum class ButtonState { IDLE, CONNECTING, CONNECTED }

    private fun setButtonState(state: ButtonState) {
        when (state) {
            ButtonState.IDLE -> {
                connectButton.text = "Подключиться"
                connectButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                connectButton.visibility = View.VISIBLE
                connectProgress.visibility = View.GONE
                canButton.isEnabled = false
            }
            ButtonState.CONNECTING -> {
                connectButton.text = "Отменить"
                connectButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
                connectButton.visibility = View.VISIBLE
                connectProgress.visibility = View.VISIBLE
                canButton.isEnabled = false
            }
            ButtonState.CONNECTED -> {
                connectButton.text = "Отключиться"
                connectButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336"))
                connectButton.visibility = View.VISIBLE
                connectProgress.visibility = View.GONE
                canButton.isEnabled = true
            }
        }
    }

    private fun updateStatusBar(status: TBoxStatus) {
        when (status.type) {
            TBoxStatusType.CONNECTING -> updateStatusText("Connecting: ${status.message}", "#FF9800")
            TBoxStatusType.CONNECTED -> updateStatusText("Connected: ${status.message}", "#4CAF50")
            TBoxStatusType.DISCONNECTED -> updateStatusText("Disconnected: ${status.message}", "#F44336")
            TBoxStatusType.UDP_BIND_FAILED,
            TBoxStatusType.UDP_RECEIVE_ERROR,
            TBoxStatusType.UDP_SEND_ERROR,
            TBoxStatusType.SERVICE_ERROR,
            TBoxStatusType.TCP_SERVER_ERROR -> updateStatusText("Error: ${status.message}", "#F44336")
            TBoxStatusType.SERVICE_STOPPED -> updateStatusText("Stopped: ${status.message}", "#FF9800")
            else -> {}
        }
    }

    private fun updateStatusText(text: String, color: String) {
        tvStatus.text = "Status: $text"
        tvStatus.setTextColor(Color.parseColor(color))
    }

    override fun onDestroy() {
        super.onDestroy()
        tboxClient.destroy()
    }
}
