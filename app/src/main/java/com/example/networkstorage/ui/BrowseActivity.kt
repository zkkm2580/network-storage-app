package com.example.networkstorage.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.example.networkstorage.client.IStorageClient
import com.example.networkstorage.client.StorageException
import com.example.networkstorage.data.StorageAccount
import com.example.networkstorage.data.StorageRepository
import com.example.networkstorage.model.RemoteFile
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * 浏览远程目录：目录导航 + 下载 / 上传 / 新建目录 / 删除。
 * 网络操作在后台线程串行执行，回到主线程前检查生命周期与请求序号，
 * 避免销毁后刷新 UI 以及快速点击造成的乱序覆盖。
 */
class BrowseActivity : Activity() {

    companion object {
        const val EXTRA_ACCOUNT_ID = "account_id"
        private const val REQUEST_PICK_FILE = 1001
        private const val MAX_UPLOAD_BYTES = 16 * 1024 * 1024
    }

    private lateinit var repo: StorageRepository
    private var account: StorageAccount? = null
    private var client: IStorageClient? = null

    @Volatile
    private var connected = false
    private val clientLock = Any()
    private val requestSeq = AtomicLong(0)

    private lateinit var pathView: TextView
    private lateinit var listView: ListView
    private val items = ArrayList<RemoteFile>()
    private val fileAdapter = FileAdapter()
    private val history = ArrayDeque<String>()
    private var currentPath = "/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = StorageRepository(this)

        val acc = intent.getStringExtra(EXTRA_ACCOUNT_ID)?.let { repo.find(it) }
        if (acc == null) {
            Toast.makeText(this, "配置不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        account = acc
        title = acc.name

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val upBtn = Button(this).apply {
            text = "↑ 上级"
            setOnClickListener { goUp() }
        }
        bar.addView(upBtn)
        pathView = TextView(this).apply {
            text = "/"
            setPadding(16, 0, 16, 0)
        }
        bar.addView(pathView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val uploadBtn = Button(this).apply {
            text = "上传文件"
            setOnClickListener { pickFile() }
        }
        actions.addView(uploadBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val mkdirBtn = Button(this).apply {
            text = "新建目录"
            setOnClickListener { promptCreateDirectory() }
        }
        actions.addView(mkdirBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)

        listView = ListView(this).apply {
            adapter = fileAdapter
            setOnItemClickListener { _, _, position, _ ->
                val f = items.getOrNull(position) ?: return@setOnItemClickListener
                if (f.isDirectory) {
                    history.push(currentPath)
                    load(f.path)
                } else {
                    showFileActions(f)
                }
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        load("/")
    }

    override fun onDestroy() {
        super.onDestroy()
        requestSeq.incrementAndGet()
        try {
            client?.disconnect()
        } catch (ignored: Exception) {
        }
        client = null
        connected = false
    }

    private fun goUp() {
        if (currentPath == "/") {
            toast("已经是根目录")
            return
        }
        val prev = if (history.isEmpty()) "/" else history.pop()
        load(prev)
    }

    private fun load(path: String) {
        val seq = requestSeq.incrementAndGet()
        pathView.text = "加载中… $path"
        listView.isEnabled = false

        Thread {
            var error: String? = null
            var result: List<RemoteFile> = emptyList()
            try {
                result = synchronized(clientLock) {
                    obtainClientLocked().listFiles(path)
                }
            } catch (e: Exception) {
                connected = false
                error = e.message ?: e.javaClass.simpleName
            }

            if (!isFinishing && !isDestroyed) {
                runOnUiThread {
                    if (isFinishing || isDestroyed || seq != requestSeq.get()) return@runOnUiThread
                    listView.isEnabled = true
                    if (error != null) {
                        pathView.text = currentPath
                        Toast.makeText(this, "加载失败: $error", Toast.LENGTH_LONG).show()
                    } else {
                        currentPath = path
                        items.clear()
                        items.addAll(result)
                        fileAdapter.notifyDataSetChanged()
                        pathView.text = "$path   (${result.size} 项)"
                    }
                }
            }
        }.start()
    }

    /** 串行执行一个写操作，完成后刷新当前目录。 */
    private fun runNetwork(label: String, block: (IStorageClient) -> String) {
        toast("$label 中…")
        Thread {
            val message = try {
                synchronized(clientLock) { block(obtainClientLocked()) }
            } catch (e: Exception) {
                connected = false
                "$label 失败: ${e.message ?: e.javaClass.simpleName}"
            }
            if (!isFinishing && !isDestroyed) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    load(currentPath)
                }
            }
        }.start()
    }

    private fun obtainClientLocked(): IStorageClient {
        val c = client ?: account!!.createClient(File(filesDir, "ssh")).also { client = it }
        if (!connected) {
            if (!c.connect()) throw StorageException("无法连接到服务器")
            connected = true
        }
        return c
    }

    private fun showFileActions(f: RemoteFile) {
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(arrayOf("下载到应用目录", "删除", "查看详情")) { _, which ->
                when (which) {
                    0 -> download(f)
                    1 -> confirmDelete(f)
                    else -> Toast.makeText(
                        this,
                        "路径: ${f.path}\n大小: ${RemoteFile.humanSize(f.size)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }

    private fun download(f: RemoteFile) {
        runNetwork("下载") { c ->
            val dir = File(filesDir, "downloads")
            if (!dir.exists() && !dir.mkdirs()) throw StorageException("无法创建下载目录")
            val outFile = File(dir, safeLocalName(f.name))
            c.openInputStream(f.path).use { input ->
                FileOutputStream(outFile).use { out -> input.copyTo(out) }
            }
            "已保存到 ${outFile.absolutePath}"
        }
    }

    private fun confirmDelete(f: RemoteFile) {
        AlertDialog.Builder(this)
            .setTitle("删除 ${f.name}")
            .setMessage("删除后不可恢复；目录删除行为取决于服务器（部分协议要求目录为空）。")
            .setPositiveButton("删除") { _, _ ->
                runNetwork("删除") { c ->
                    if (!c.delete(f.path)) throw StorageException("服务器拒绝了删除请求")
                    "已删除 ${f.name}"
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptCreateDirectory() {
        val input = EditText(this).apply { hint = "目录名称" }
        AlertDialog.Builder(this)
            .setTitle("新建目录")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (!isSafeName(name)) {
                    toast("目录名不合法")
                } else {
                    val target = if (currentPath == "/") "/$name" else "$currentPath/$name"
                    runNetwork("创建目录") { c ->
                        if (!c.createDirectory(target)) throw StorageException("服务器拒绝了创建请求")
                        "已创建 $name"
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_FILE)
        } catch (e: Exception) {
            toast("无法打开文件选择器: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val rawName = queryDisplayName(uri) ?: "upload_${System.currentTimeMillis()}"
        val name = safeLocalName(rawName)
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > MAX_UPLOAD_BYTES) {
                        throw StorageException("文件超过 16 MB，暂不支持上传大文件")
                    }
                    buffer.write(chunk, 0, n)
                }
                buffer.toByteArray()
            } ?: throw StorageException("无法读取所选文件")
        } catch (e: Exception) {
            toast(e.message ?: "读取文件失败")
            return
        }
        val target = if (currentPath == "/") "/$name" else "$currentPath/$name"
        runNetwork("上传") { c ->
            if (!c.upload(target, bytes)) throw StorageException("服务器拒绝了上传请求")
            "已上传 $name"
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\')

    private fun safeLocalName(name: String): String = name
        .replace('/', '_')
        .replace('\\', '_')
        .replace(':', '_')
        .replace('*', '_')
        .replace('?', '_')
        .replace('"', '_')
        .replace('<', '_')
        .replace('>', '_')
        .replace('|', '_')
        .ifEmpty { "download" }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private inner class FileAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@BrowseActivity).apply {
                setPadding(32, 26, 32, 26)
                textSize = 15f
            }
            tv.text = items[position].displayText()
            return tv
        }
    }
}
