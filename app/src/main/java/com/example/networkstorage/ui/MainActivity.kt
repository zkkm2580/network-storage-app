package com.example.networkstorage.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.example.networkstorage.data.StorageAccount
import com.example.networkstorage.data.StorageRepository

/**
 * 首页：列出已配置的网络存储，支持添加、删除、进入浏览。
 */
class MainActivity : Activity() {

    private lateinit var repo: StorageRepository
    private lateinit var listView: ListView
    private val accountAdapter = AccountAdapter()
    private var accounts: MutableList<StorageAccount> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = StorageRepository(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val addBtn = Button(this).apply {
            text = "＋ 添加网络存储"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AddStorageActivity::class.java))
            }
        }
        root.addView(
            addBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        listView = ListView(this).apply {
            adapter = accountAdapter
            setOnItemClickListener { _, _, position, _ ->
                val acc = accounts.getOrNull(position)
                if (acc != null) {
                    startActivity(
                        Intent(this@MainActivity, BrowseActivity::class.java)
                            .putExtra(BrowseActivity.EXTRA_ACCOUNT_ID, acc.id)
                    )
                }
            }
            setOnItemLongClickListener { _, _, position, _ ->
                val acc = accounts.getOrNull(position)
                if (acc != null) confirmDelete(acc)
                true
            }
        }
        root.addView(
            listView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
        title = "网络存储"
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        accounts = repo.getAll().toMutableList()
        accountAdapter.notifyDataSetChanged()
        if (accounts.isEmpty()) {
            toast("还没有配置，点击上方按钮添加")
        }
    }

    private fun confirmDelete(acc: StorageAccount) {
        AlertDialog.Builder(this)
            .setTitle("删除配置")
            .setMessage("确定删除「" + acc.name + "」？")
            .setPositiveButton("删除") { _, _ ->
                repo.delete(acc.id)
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private inner class AccountAdapter : BaseAdapter() {
        override fun getCount(): Int = accounts.size
        override fun getItem(position: Int): Any = accounts[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setPadding(32, 28, 32, 28)
                textSize = 15f
            }
            val a = accounts[position]
            val detail = StringBuilder(a.type.label)
                .append("  ·  ")
                .append(a.host)
            if (a.path.isNotBlank()) detail.append("/").append(a.path.trim('/'))
            tv.text = a.name + "\n" + detail.toString()
            return tv
        }
    }
}
