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
import java.io.FilterInputStream
import java.io.InputStream
import java.util.EnumSet
import java.util.Locale

/**
 * SMB2 / SMB3 客户端（基于 smbj）。只访问单个共享，不支持共享枚举。
 */
class SmbClient(
    private val host: String,
    private val port: Int,
    private val shareName: String,
    private val username: String,
    private val password: String,
    private val domain: String
) : IStorageClient {

    private var smbClient: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    override fun connect(): Boolean = try {
        ensureShare()
        true
    } catch (e: Exception) {
        disconnect()
        false
    }

    override fun listFiles(path: String): List<RemoteFile> {
        val disk = ensureShare()
        val dir = toSmbPath(path)
        val out = ArrayList<RemoteFile>()
        for (info in disk.list(dir)) {
            val name = info.fileName ?: continue
            if (!isSafeEntryName(name)) continue
            val isDir = (info.fileAttributes and ATTR_DIRECTORY) != 0L
            val modified = try {
                info.lastWriteTime?.toEpochMillis() ?: 0L
            } catch (e: Exception) {
                0L
            }
            out.add(
                RemoteFile(
                    name = name,
                    path = joinRemotePath(path, name),
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
        val stream = try {
            file.inputStream
        } catch (e: Exception) {
            try {
                file.close()
            } catch (ignored: Exception) {
            }
            throw e
        }
        return object : FilterInputStream(stream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    try {
                        file.close()
                    } catch (ignored: Exception) {
                    }
                }
            }
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
            if (disk.folderExists(target)) disk.rmdir(target, true) else disk.rm(target)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun disconnect() {
        try {
            share?.close()
        } catch (ignored: Exception) {
        }
        try {
            session?.close()
        } catch (ignored: Exception) {
        }
        try {
            connection?.close()
        } catch (ignored: Exception) {
        }
        try {
            smbClient?.close()
        } catch (ignored: Exception) {
        }
        share = null
        session = null
        connection = null
        smbClient = null
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

        smbClient = client
        connection = conn
        session = sess
        share = disk
        return disk
    }

    private companion object {
        const val ATTR_DIRECTORY = 0x10L

        fun toSmbPath(appPath: String): String {
            val trimmed = appPath.trim().trim('/')
            return trimmed.replace('/', '\\')
        }
    }
}
