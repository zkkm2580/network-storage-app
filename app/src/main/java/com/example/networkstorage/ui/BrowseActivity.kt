package com.example.networkstorage.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.example.networkstorage.client.IStorageClient
import com.example.networkstorage.data.StorageAccount
import com.example.networkstorage.data.StorageRepository
import com.example.networkstorage.model.RemoteFile
import java.util.ArrayDeque

/**
 * 浏览某个网络存储的目录树。所有网络操作都在后台线程执行，
 * 结果通过 runOnUiThread 回到主线程刷新 UI。
 */
class BrowseActivity : Activity() {

    companion object {
        const val EXTRA_ACCOUNT_ID = "account_id"
    }

    private lateinit var repo: StorageRepository
    private var account: StorageAccount? = null
    private var client: IStorageClient? = null
    private var connected = false

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
        bar.addView(
            pathView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(bar)

        listView = ListView(this).apply {
            adapter = fileAdapter
            setOnItemClickListener { _, _, position, _ ->
                val f = items.getOrNull(position) ?: return@setOnItemClickListener
                if (f.isDirectory) {
                    history.push(currentPath)
                    load(f.path)
                } else {
                    Toast.makeText(
                        this@BrowseActivity,
                        f.name + "  (" + RemoteFile.humanSize(f.size) + ")",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        root.addView(
            listView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
        load("/")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            client?.disconnect()
        } catch (_: Exception) {
        }
        client = null
        connected = false
    }

    private fun goUp() {
        if (currentPath == "/") {
            Toast.makeText(this, "已经是根目录", Toast.LENGTH_SHORT).show()
            return
        }
        val prev = if (history.isEmpty()) "/" else history.pop()
        load(prev)
    }

    private fun load(path: String) {
        pathView.text = "加载中… $path"
        listView.isEnabled = false

        Thread {
            var error: String? = null
            var result: List<RemoteFile> = emptyList()
            try {
                val c = client ?: account!!.createClient().also { client = it }
                if (!connected) {
                    connected = c.connect()
                    if (!connected) throw IllegalStateException("无法连接到服务器")
                }
                result = c.listFiles(path)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }

            runOnUiThread {
                listView.isEnabled = true
                if (error != null) {
                    pathView.text = currentPath
                    Toast.makeText(
                        this,
                        "加载失败: " + error,
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    currentPath = path
                    items.clear()
                    items.addAll(result)
                    fileAdapter.notifyDataSetChanged()
                    pathView.text = path + "   (" + result.size + " 项)"
                }
            }
        }.start()
    }

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
