package com.example.networkstorage.data

import com.example.networkstorage.client.IStorageClient
import com.example.networkstorage.client.SftpClient
import com.example.networkstorage.client.SmbClient
import com.example.networkstorage.client.WebDavClient
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID

/** 支持的协议类型 */
enum class StorageType(val label: String) {
    WEBDAV("WebDAV"),
    SMB("SMB / Windows 共享"),
    SFTP("SFTP");

    companion object {
        fun fromName(name: String?): StorageType =
            entries.firstOrNull { it.name == name } ?: WEBDAV
    }
}

/**
 * 一条网络存储配置。
 *  - WEBDAV: host 为完整 URL；port 仅在 URL 未显式带端口时生效
 *  - SMB   : host 为服务器地址，port 默认 445，path 为共享名
 *  - SFTP  : host 为服务器地址，port 默认 22，path 为登录后的根目录
 */
data class StorageAccount(
    val id: String,
    val name: String,
    val type: StorageType,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val path: String,
    val domain: String = ""
) {

    /** 根据协议创建对应的客户端实例（未连接）。 */
    fun createClient(hostKeyStoreDir: File? = null): IStorageClient {
        return when (type) {
            StorageType.WEBDAV -> WebDavClient(
                baseUrl = buildWebDavUrl(host.trim().trimEnd('/'), port),
                username = username,
                password = password
            )

            StorageType.SMB -> SmbClient(
                host = host.trim(),
                port = if (port > 0) port else 445,
                shareName = path.trim().trim('/'),
                username = username,
                password = password,
                domain = domain.trim()
            )

            StorageType.SFTP -> SftpClient(
                host = host.trim(),
                port = if (port > 0) port else 22,
                username = username,
                password = password,
                basePath = path.trim().ifEmpty { "/" },
                hostKeyStoreDir = hostKeyStoreDir
            )
        }
    }

    /** 默认端口（用于表单提示与序列化回退）。 */
    fun defaultPort(): Int = when (type) {
        StorageType.WEBDAV -> if (host.startsWith("https", true)) 443 else 80
        StorageType.SMB -> 445
        StorageType.SFTP -> 22
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("type", type.name)
        o.put("host", host)
        o.put("port", port)
        o.put("username", username)
        o.put("password", password)
        o.put("path", path)
        o.put("domain", domain)
        return o
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(o: JSONObject): StorageAccount = StorageAccount(
            id = o.optString("id", newId()),
            name = o.optString("name", "未命名"),
            type = StorageType.fromName(o.optString("type")),
            host = o.optString("host"),
            port = o.optInt("port", 0),
            username = o.optString("username"),
            password = o.optString("password"),
            path = o.optString("path"),
            domain = o.optString("domain")
        )

        /** URL 未带端口时把表单端口合并进去，尊重 URL 里已有的显式端口。 */
        private fun buildWebDavUrl(raw: String, port: Int): String {
            if (port <= 0) return raw
            return try {
                val uri = URI(raw)
                if (uri.scheme == null || uri.host == null || uri.port != -1) return raw
                val authority = buildString {
                    val info = uri.userInfo
                    if (!info.isNullOrEmpty()) append(info).append('@')
                    val h = uri.host
                    if (h.contains(':') && !h.startsWith("[")) {
                        append('[').append(h).append(']')
                    } else {
                        append(h)
                    }
                    append(':').append(port)
                }
                val path = uri.rawPath ?: ""
                val query = uri.rawQuery?.let { "?$it" } ?: ""
                "${uri.scheme}://$authority$path$query"
            } catch (e: Exception) {
                raw
            }
        }
    }
}
