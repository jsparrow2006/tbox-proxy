package dashengineering.jetour.TboxCore.demo

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dashing.tbox.proxy.demo.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ITBoxMessage(
    val type: String,
    val message: String,
    val time: Long = System.currentTimeMillis(),
)

class PacketAdapter(
    private val recyclerView: RecyclerView,
    private val onScrollStateChanged: (isAtBottom: Boolean) -> Unit = {}
) : RecyclerView.Adapter<PacketAdapter.ViewHolder>() {
    private val packets = mutableListOf<ITBoxMessage>()
    private var isUserScrolledUp = false

    init {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    isUserScrolledUp = !isNearBottom()
                }
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val atBottom = isNearBottom()
                    isUserScrolledUp = !atBottom
                    onScrollStateChanged(atBottom)
                }
            }
        })
    }

    private fun isNearBottom(): Boolean {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return true
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        val totalItems = itemCount
        return totalItems == 0 || lastVisible >= totalItems - 3
    }

    fun addPacket(packet: ITBoxMessage) {
        packets.add(packet)
        Log.d("PROXY_LIB", packet.message)
        notifyItemInserted(packets.size - 1)
        if (!isUserScrolledUp) {
            recyclerView.scrollToPosition(packets.size - 1)
        }
    }

    fun scrollToBottom() {
        if (packets.isNotEmpty()) {
            recyclerView.scrollToPosition(packets.size - 1)
            isUserScrolledUp = false
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_packet, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val p = packets[position]
        holder.timeMessage.text = getDateFromTimeStamp(p.time)
        holder.message.text = p.message
        holder.typeMessage.text = p.type
    }

    private fun getDateFromTimeStamp(timeStamp: Long, pattern: String = "HH:mm:ss.SSS"): String {
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timeStamp))
    }

    fun clear() {
        packets.clear()
    }

    fun saveToFile(context: Context, filename: String = "tbox_log_${getDateFromTimeStamp(System.currentTimeMillis(), "dd.MM.yyy_HH.mm.ss")}.txt") {
        val lines = packets.map { "${getDateFromTimeStamp(it.time)} | ${it.type} | ${it.message}" }
        val text = lines.joinToString("\n")

        try {
            val resolver = context.contentResolver
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            } else {
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), filename)
                Uri.fromFile(file)
            }

            uri?.let {
                resolver.openOutputStream(uri).use { stream ->
                    stream?.write(text.toByteArray())
                }
                Toast.makeText(context, "Файл сохранён в Downloads/$filename", Toast.LENGTH_LONG).show()
            } ?: run {
                throw Exception("Не удалось создать файл")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun getItemCount() = packets.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        var message = view.findViewById<TextView>(R.id.message)
        var timeMessage = view.findViewById<TextView>(R.id.time)
        var typeMessage = view.findViewById<TextView>(R.id.type)
        var messageRow = view.findViewById<LinearLayout>(R.id.messageRow)
    }
}
