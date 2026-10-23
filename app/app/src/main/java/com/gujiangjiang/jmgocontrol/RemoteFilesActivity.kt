package com.gujiangjiang.jmgocontrol

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gujiangjiang.jmgocontrol.core.AppPrefs
import com.gujiangjiang.jmgocontrol.databinding.ActivityRemoteFilesBinding
import com.gujiangjiang.jmgocontrol.databinding.ItemFileBinding
import com.gujiangjiang.jmgocontrol.remote.AgentClient
import org.json.JSONArray
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RemoteFilesActivity : AppCompatActivity() {

    private lateinit var b: ActivityRemoteFilesBinding
    private lateinit var prefs: AppPrefs
    private var ip = ""
    private var currentPath = "/sdcard"
    private val items = ArrayList<FileItem>()
    private lateinit var adapter: FileAdapter
    private var loading = false

    data class FileItem(val name: String, val dir: Boolean, val size: Long, val mtime: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRemoteFilesBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = AppPrefs(this)
        ip = intent.getStringExtra(EXTRA_IP) ?: prefs.lastRemoteIp

        adapter = FileAdapter(items) { onItem(it) }
        b.fsList.layoutManager = LinearLayoutManager(this)
        b.fsList.adapter = adapter

        b.fsUp.setOnClickListener { goUp() }
        b.fsRefresh.setOnClickListener { load(currentPath) }
        b.fsUpload.setOnClickListener { pickUpload() }

        load(currentPath)
    }

    private fun client(): AgentClient =
        AgentClient(ip, AgentClient.DEFAULT_PORT, prefs.agentClientPin)

    private fun ui(block: () -> Unit) = runOnUiThread {
        if (!isFinishing) block()
    }

    private fun load(path: String) {
        if (loading) return
        loading = true
        b.fsPath.text = path
        Thread {
            val r = client().get("/files?path=" + URLEncoder.encode(path, "UTF-8"), 15000)
            val j = r.json()
            ui {
                loading = false
                if (j == null) {
                    Toast.makeText(
                        this,
                        getString(R.string.fs_fail, r.error ?: r.text().take(120)),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@ui
                }
                currentPath = j.optString("path", path)
                b.fsPath.text = currentPath
                items.clear()
                val arr: JSONArray = j.optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    items.add(
                        FileItem(
                            name = o.optString("name"),
                            dir = o.optBoolean("dir"),
                            size = o.optLong("size"),
                            mtime = o.optLong("mtime")
                        )
                    )
                }
                adapter.notifyDataSetChanged()
                if (items.isEmpty()) {
                    Toast.makeText(this, R.string.fs_empty, Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun goUp() {
        if (currentPath == "/" || currentPath.isEmpty()) return
        val parent = currentPath.trimEnd('/').substringBeforeLast('/', "/")
        load(if (parent.isEmpty()) "/" else parent)
    }

    private fun onItem(item: FileItem) {
        if (item.dir) {
            load(currentPath.trimEnd('/') + "/" + item.name)
        } else {
            download(item)
        }
    }

    private fun download(item: FileItem) {
        Toast.makeText(this, getString(R.string.fs_downloading, item.name), Toast.LENGTH_SHORT).show()
        Thread {
            val full = currentPath.trimEnd('/') + "/" + item.name
            val r = client().get("/file?path=" + URLEncoder.encode(full, "UTF-8"), 120000)
            if (!r.ok || r.body.isEmpty()) {
                ui {
                    Toast.makeText(
                        this,
                        getString(R.string.fs_fail, r.error ?: r.text().take(120)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@Thread
            }
            val dir = getExternalFilesDir("downloads") ?: filesDir
            val f = File(dir, item.name)
            f.writeBytes(r.body)
            ui {
                Toast.makeText(
                    this,
                    getString(R.string.fs_saved, f.absolutePath),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun pickUpload() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(i, REQ_UPLOAD)
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.tb_pick_fail, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_UPLOAD || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        Toast.makeText(this, R.string.fs_uploading, Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val resolver = contentResolver
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                var fileName = "upload.bin"
                resolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) fileName = c.getString(idx) ?: fileName
                }
                val r = client().post(
                    "/upload?path=" + URLEncoder.encode(currentPath, "UTF-8") +
                        "&name=" + URLEncoder.encode(fileName, "UTF-8"),
                    bytes
                )
                val j = r.json()
                val ok = j?.optBoolean("ok") ?: false
                ui {
                    Toast.makeText(
                        this,
                        getString(
                            if (ok) R.string.fs_upload_ok else R.string.fs_fail,
                            j?.optString("path") ?: (r.error ?: "")
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    if (ok) load(currentPath)
                }
            } catch (t: Throwable) {
                ui {
                    Toast.makeText(
                        this,
                        getString(R.string.fs_fail, t.message ?: t.javaClass.simpleName),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private class FileAdapter(
        private val items: List<FileItem>,
        private val onClick: (FileItem) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.VH>() {

        class VH(val b: ItemFileBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = items[position]
            holder.b.fileIcon.setImageResource(
                if (f.dir) R.drawable.ic_folder else R.drawable.ic_file
            )
            holder.b.fileName.text = f.name
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            holder.b.fileSub.text = if (f.dir) {
                "目录"
            } else {
                df.format(Date(f.mtime)) + " · " + formatSize(f.size)
            }
            holder.b.root.setOnClickListener { onClick(f) }
        }

        private fun formatSize(size: Long): String {
            if (size < 1024) return "$size B"
            val kb = size / 1024.0
            if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
            return String.format(Locale.US, "%.2f GB", mb / 1024.0)
        }
    }

    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_NAME = "name"
        private const val REQ_UPLOAD = 2102
    }
}
