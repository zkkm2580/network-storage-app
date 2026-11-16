package com.example.networkstorage.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.example.networkstorage.client.IStorageClient
import com.example.networkstorage.data.StorageAccount
import com.example.networkstorage.data.StorageRepository
import com.example.networkstorage.data.StorageType
import java.io.File

/** 添加网络存储：选择协议 → 填写地址与凭据 → 后台连接校验 → 加密保存。 */
class AddStorageActivity : Activity() {

    private lateinit var repo: StorageRepository

    private lateinit var nameEt: EditText
    private lateinit var hostEt: EditText
    private lateinit var portEt: EditText
    private lateinit var userEt: EditText
    private lateinit var passEt: EditText
    private lateinit var pathEt: EditText
    private lateinit var domainEt: EditText
    private lateinit var typeSpinner: Spinner
    private lateinit var saveBtn: Button
    private lateinit var pathLabel: TextView

    private val types = StorageType.entries.toList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = StorageRepository(this)
        title = "添加网络存储"
        if (repo.isMemoryOnly) {
            toast("设备加密不可用：配置仅保存在内存，退出后丢失")
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 48)
        }

        container.addView(label("协议类型"))
        typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AddStorageActivity,
                android.R.layout.simple_spinner_item,
                types.map { it.label }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        container.addView(typeSpinner)

        nameEt = field(container, "名称（备注）", "例如：家里的 NAS")
        hostEt = field(container, "服务器地址", "SMB/SFTP 填 IP 或域名；WebDAV 填完整 URL（http:// 或 https://）")
        portEt = field(container, "端口（留空用默认）", "445 / 22 / 80 / 443")
        portEt.inputType = InputType.TYPE_CLASS_NUMBER
        userEt = field(container, "用户名", "可留空表示匿名/游客")
        passEt = field(container, "密码", "")
        passEt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        pathLabel = label("共享名 / 路径")
        container.addView(pathLabel)
        pathEt = EditText(this).apply { hint = "SMB 填共享名，SFTP 填根目录" }
        container.addView(pathEt)

        domainEt = field(container, "SMB 域 / 工作组（可选）", "例如 WORKGROUP")

        saveBtn = Button(this).apply {
            text = "保存并测试连接"
            setOnClickListener { submit() }
        }
        container.addView(
            saveBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        typeSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                applyTypeHints(types[position])
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })
        applyTypeHints(types[0])

        val scroll = ScrollView(this).apply { addView(container) }
        setContentView(scroll)
    }

    private fun applyTypeHints(type: StorageType) {
        when (type) {
            StorageType.WEBDAV -> {
                hostEt.hint = "http://nas.example.com:5006/dav"
                pathLabel.text = "路径（WebDAV 请写在上面的 URL 里）"
                pathEt.hint = "留空即可"
                portEt.hint = "URL 未带端口时在这里指定"
            }

            StorageType.SMB -> {
                hostEt.hint = "192.168.1.10"
                pathLabel.text = "共享名"
                pathEt.hint = "例如 volume1 或 D"
                portEt.hint = "445"
            }

            StorageType.SFTP -> {
                hostEt.hint = "192.168.1.10"
                pathLabel.text = "根目录"
                pathEt.hint = "例如 /volume1/media"
                portEt.hint = "22"
            }
        }
    }

    private fun submit() {
        val host = hostEt.text.toString().trim()
        if (host.isEmpty()) {
            toast("请填写服务器地址")
            return
        }
        val type = types[typeSpinner.selectedItemPosition]

        val account = StorageAccount(
            id = StorageAccount.newId(),
            name = nameEt.text.toString().trim().ifEmpty { host },
            type = type,
            host = host,
            port = portEt.text.toString().trim().toIntOrNull() ?: 0,
            username = userEt.text.toString(),
            password = passEt.text.toString(),
            path = pathEt.text.toString().trim(),
            domain = domainEt.text.toString().trim()
        )

        saveBtn.isEnabled = false
        saveBtn.text = "正在连接…"

        Thread {
            var usedClient: IStorageClient? = null
            var ok = false
            var detail = ""
            try {
                val client = account.createClient(File(filesDir, "ssh"))
                usedClient = client
                ok = client.connect()
                if (ok) {
                    val items = try {
                        client.listFiles("/").size
                    } catch (e: Exception) {
                        -1
                    }
                    detail = if (items >= 0) "根目录 $items 项" else "已连接（根目录不可列出）"
                } else {
                    detail = "连接被拒绝，请检查地址、端口与凭据"
                }
            } catch (e: Exception) {
                detail = e.message ?: e.javaClass.simpleName
            } finally {
                try {
                    usedClient?.disconnect()
                } catch (ignored: Exception) {
                }
            }

            if (!isFinishing && !isDestroyed) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    saveBtn.isEnabled = true
                    saveBtn.text = "保存并测试连接"
                    if (ok) {
                        repo.upsert(account)
                        toast("保存成功：" + detail)
                        finish()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("连接失败")
                            .setMessage(detail)
                            .setPositiveButton("仍然保存") { _, _ ->
                                repo.upsert(account)
                                finish()
                            }
                            .setNegativeButton("返回修改", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setPadding(0, 24, 0, 4)
    }

    private fun field(container: LinearLayout, title: String, hint: String): EditText {
        container.addView(label(title))
        val et = EditText(this).apply { this.hint = hint }
        container.addView(et)
        return et
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
