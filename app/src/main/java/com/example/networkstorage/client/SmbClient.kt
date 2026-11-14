package com.example.networkstorage.client

import com.example.networkstorage.model.RemoteFile
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.EnumSet
import java.util.Locale

/**
 * SMB2 / SMB3 客户端（基于 smbj）。
 *
 * @param shareName 共享名，例如 Windows 的 "D" 或 NAS 的 "volume1"
 * @param domain    Windows 域或工作组名，可留空
 */
class SmbClient(
    private val host: String,
    private val port: Int,
    private val shareName: String,
    private val username: String,
    private val password: String,
    private val domain: String
) : IStorageClient {

    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    override fun connect(): Boolean {
        return try {
            ensureShare()
            true
        } catch (e: Exception) {
            disconnect()
            false
        }
    }

    override fun listFiles(path: String): List<RemoteFile> {
        val disk = ensureShare()
        val dir = toSmbPath(path)
        val out = ArrayList<RemoteFile>()
        for (info in disk.list(dir)) {
            val name = info.fileName ?: continue
            if (name == "." || name == "..") continue
            val isDir = (info.fileAttributes and ATTR_DIRECTORY) != 0L
            val modified = try {
                info.lastWriteTime?.toEpochMillis() ?: 0L
            } catch (e: Exception) {
                0L
            }
            out.add(
                RemoteFile(
                    name = name,
                    path = joinPath(path, name),
                    size = if (isDir) 0L else info.endOfFile,
                    isDirectory = isDir,
                    lastModified = modified
                )
            )
        }
        out.sortWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) })
        return out
    }

    override fun openInputStream(path: String): InputStream {
        val disk = ensureShare()
        val file = disk.openFile(
            toSmbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        file.use { f ->
            val bytes = f.inputStream.use { it.readBytes() }
            return ByteArrayInputStream(bytes)
        }
    }

    override fun upload(path: String, data: ByteArray): Boolean {
        val disk = ensureShare()
        val file = disk.openFile(
            toSmbPath(path),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null
        )
        file.use { f ->
            f.outputStream.use { it.write(data) }
            return true
        }
    }

    override fun createDirectory(path: String): Boolean {
        val disk = ensureShare()
        return try {
            disk.mkdir(toSmbPath(path))
            true
        } catch (e: Exception) {
            // 已存在等价于成功
            try {
                disk.folderExists(toSmbPath(path))
            } catch (ignored: Exception) {
                false
            }
        }
    }

    override fun delete(path: String): Boolean {
        val disk = ensureShare()
        val target = toSmbPath(path)
        return try {
            if (disk.folderExists(target)) {
                disk.rmdir(target, true)
            } else {
                disk.rm(target)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun disconnect() {
        try {
            share?.close()
        } catch (_: Exception) {
        }
        try {
            session?.close()
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        share = null
        session = null
        connection = null
    }

    // ---------------------------------------------------------------- helpers

    private fun ensureShare(): DiskShare {
        val current = share
        if (current != null && current.isConnected) return current
        disconnect()

        val client = SMBClient()
        val conn = client.connect(host, port)
        val auth = AuthenticationContext(username, password.toCharArray(), domain)
        val sess = conn.authenticate(auth)
        val disk = sess.connectShare(shareName) as? DiskShare
            ?: throw StorageException("无法打开共享: $shareName")

        connection = conn
        session = sess
        share = disk
        return disk
    }

    private companion object {
        /** FILE_ATTRIBUTE_DIRECTORY = 0x10 */
        const val ATTR_DIRECTORY = 0x10L

        fun toSmbPath(appPath: String): String {
            val trimmed = appPath.trim().trim('/')
            return trimmed.replace('/', '\\')
        }

        fun joinPath(parent: String, name: String): String {
            val p = parent.trimEnd('/')
            return if (p.isEmpty()) "/$name" else "$p/$name"
        }
    }
}
