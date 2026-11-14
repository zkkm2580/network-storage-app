package com.example.networkstorage.model

/**
 * 统一的远程文件/目录模型。
 * 无论底层是 WebDAV、SMB 还是 SFTP，UI 层只处理这一种类型。
 */
data class RemoteFile(
    val name: String,
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
    val lastModified: Long
) {
    /** 供 ListView 显示的文本 */
    fun displayText(): String {
        val prefix = if (isDirectory) "[目录] " else "[文件] "
        val suffix = if (isDirectory) "" else "  (" + humanSize(size) + ")"
        return prefix + name + suffix
    }

    companion object {
        fun humanSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format("%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format("%.1f MB", mb)
            return String.format("%.2f GB", mb / 1024.0)
        }
    }
}
