package com.example.networkstorage.client

import com.example.networkstorage.model.RemoteFile
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale
import java.util.Properties
import java.util.Vector

/**
 * SFTP 客户端（基于 JSch）。
 *
 * @param basePath 登录后使用的根目录，例如 "/volume1" 或 "/home/user"
 */
class SftpClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val basePath: String
) : IStorageClient {

    private var session: Session? = null
    private var channel: ChannelSftp? = null

    override fun connect(): Boolean {
        return try {
            ensureChannel()
            true
        } catch (e: Exception) {
            disconnect()
            false
        }
    }

    override fun listFiles(path: String): List<RemoteFile> {
        val sftp = ensureChannel()
        val dir = toAbsolute(path)

        @Suppress("UNCHECKED_CAST")
        val entries = sftp.ls(dir) as Vector<ChannelSftp.LsEntry>

        val out = ArrayList<RemoteFile>()
        for (entry in entries) {
            val name = entry.filename ?: continue
            if (name == "." || name == "..") continue
            val attrs = entry.attrs ?: continue
            val isDir = attrs.isDir
            out.add(
                RemoteFile(
                    name = name,
                    path = joinPath(path, name),
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
        val sftp = ensureChannel()
        val remote = sftp.get(toAbsolute(path))
        remote.use { return ByteArrayInputStream(it.readBytes()) }
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
            // 目录已存在
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
        } catch (_: Exception) {
        }
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        channel = null
        session = null
    }

    // ---------------------------------------------------------------- helpers

    private fun ensureChannel(): ChannelSftp {
        val current = channel
        if (current != null && current.isConnected) return current
        disconnect()

        val jsch = JSch()
        val sess = jsch.getSession(username, host, port)
        sess.setPassword(password)
        val config = Properties()
        config["StrictHostKeyChecking"] = "no"
        config["PreferredAuthentications"] = "password,keyboard-interactive"
        sess.setConfig(config)
        sess.timeout = 20000
        sess.connect(20000)

        val ch = sess.openChannel("sftp")
        ch.connect(20000)
        val sftp = ch as ChannelSftp

        val base = basePath.trim().ifEmpty { "/" }
        try {
            sftp.cd(base)
        } catch (e: Exception) {
            // 目录不存在时退回用户家目录
        }

        session = sess
        channel = sftp
        return sftp
    }

    private fun toAbsolute(path: String): String {
        val base = basePath.trim().trimEnd('/')
        val p = path.trim()
        return when {
            p == "/" || p.isEmpty() -> base.ifEmpty { "/" }
            base.isEmpty() -> p
            else -> base + p
        }
    }

    private companion object {
        fun joinPath(parent: String, name: String): String {
            val p = parent.trimEnd('/')
            return if (p.isEmpty()) "/$name" else "$p/$name"
        }
    }
}
