package com.example.networkstorage.client

import com.example.networkstorage.model.RemoteFile
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.Properties
import java.util.Vector

/**
 * SFTP 客户端（JSch / mwiede fork）。
 * 主机密钥采用 TOFU：首次连接锁定公钥并写入应用私有 known_hosts，
 * 之后以 StrictHostKeyChecking=yes 严格校验，密钥变更即拒绝连接。
 */
class SftpClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val basePath: String,
    private val hostKeyStoreDir: File? = null
) : IStorageClient {

    private var session: Session? = null
    private var channel: ChannelSftp? = null

    override fun connect(): Boolean = try {
        ensureChannel()
        true
    } catch (e: Exception) {
        disconnect()
        false
    }

    override fun listFiles(path: String): List<RemoteFile> {
        val sftp = ensureChannel()
        val dir = toAbsolute(path)

        @Suppress("UNCHECKED_CAST")
        val entries = sftp.ls(dir) as Vector<ChannelSftp.LsEntry>

        val out = ArrayList<RemoteFile>()
        for (entry in entries) {
            val name = entry.filename ?: continue
            if (!isSafeEntryName(name)) continue
            val attrs = entry.attrs ?: continue
            val isDir = attrs.isDir
            out.add(
                RemoteFile(
                    name = name,
                    path = joinRemotePath(path, name),
                    size = if (isDir) 0L else attrs.size,
                    isDirectory = isDir,
                    lastModified = attrs.mTime.toLong() * 1000L
                )
            )
        }
        out.sortWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) })
        return out
    }

    override fun openInputStream(path: String): InputStream {
        return ensureChannel().get(toAbsolute(path))
    }

    override fun upload(path: String, data: ByteArray): Boolean {
        val sftp = ensureChannel()
        ByteArrayInputStream(data).use { input ->
            sftp.put(input, toAbsolute(path))
        }
        return true
    }

    override fun createDirectory(path: String): Boolean {
        val sftp = ensureChannel()
        return try {
            sftp.mkdir(toAbsolute(path))
            true
        } catch (e: Exception) {
            try {
                sftp.stat(toAbsolute(path)).isDir
            } catch (ignored: Exception) {
                false
            }
        }
    }

    override fun delete(path: String): Boolean {
        val sftp = ensureChannel()
        val target = toAbsolute(path)
        return try {
            val isDir = try {
                sftp.stat(target).isDir
            } catch (e: Exception) {
                false
            }
            if (isDir) sftp.rmdir(target) else sftp.rm(target)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun disconnect() {
        try {
            channel?.disconnect()
        } catch (ignored: Exception) {
        }
        try {
            session?.disconnect()
        } catch (ignored: Exception) {
        }
        channel = null
        session = null
    }

    // ---------------------------------------------------------------- helpers

    private fun ensureChannel(): ChannelSftp {
        val current = channel
        if (current != null && current.isConnected) return current
        disconnect()

        val dir = hostKeyStoreDir
            ?: throw StorageException("SFTP 缺少主机密钥存储目录，已拒绝连接")
        val khFile = try {
            if (!dir.exists() && !dir.mkdirs()) {
                throw StorageException("无法创建主机密钥目录")
            }
            File(dir, "known_hosts")
        } catch (e: Exception) {
            throw StorageException("无法准备主机密钥存储: ${e.message}", e)
        }

        val jsch = JSch()
        val hasPinnedKeys = khFile.exists() && khFile.length() > 0L
        if (hasPinnedKeys) {
            try {
                jsch.setKnownHosts(khFile.absolutePath)
            } catch (e: Exception) {
                throw StorageException("无法读取主机密钥库: ${e.message}", e)
            }
        }

        val sess = jsch.getSession(username, host, port)
        sess.setPassword(password)
        val config = Properties()
        config["StrictHostKeyChecking"] = if (hasPinnedKeys) "yes" else "no"
        config["PreferredAuthentications"] = "password,keyboard-interactive"
        sess.setConfig(config)
        sess.timeout = 20000
        sess.connect(20000)

        if (!hasPinnedKeys) {
            val hk = sess.hostKey
            if (hk == null) {
                sess.disconnect()
                throw StorageException("未能获取服务器主机密钥，已取消连接")
            }
            val label = if (port == 22) host else "[$host]:$port"
            try {
                khFile.appendText("$label ${hk.type} ${hk.key}\n")
            } catch (e: Exception) {
                sess.disconnect()
                throw StorageException("无法保存主机密钥，已取消连接: ${e.message}", e)
            }
        }

        val ch = sess.openChannel("sftp")
        ch.connect(20000)
        val sftp = ch as ChannelSftp

        session = sess
        channel = sftp
        return sftp
    }

    /** 统一按绝对路径寻址，不再依赖 cd()，避免相对 basePath 造成双重前缀。 */
    private fun toAbsolute(path: String): String {
        val rawBase = basePath.trim()
        val base = when {
            rawBase.isEmpty() || rawBase == "/" -> ""
            rawBase.startsWith("/") -> rawBase.trimEnd('/')
            else -> "/" + rawBase.trimEnd('/')
        }
        val raw = path.trim()
        val rel = if (raw.startsWith("/")) raw else "/$raw"
        val clean = if (rel.length > 1) rel.trimEnd('/') else rel
        if (clean == "/") return base.ifEmpty { "/" }
        return base + clean
    }
}
