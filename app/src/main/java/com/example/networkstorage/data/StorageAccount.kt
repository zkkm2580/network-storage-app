package com.example.networkstorage.data

import com.example.networkstorage.client.IStorageClient
import com.example.networkstorage.client.SftpClient
import com.example.networkstorage.client.SmbClient
import com.example.networkstorage.client.WebDavClient
import org.json.JSONObject
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
 *
 * 字段语义随协议不同：
 *  - WEBDAV: host 保存完整的基础 URL（如 https://nas.example.com/dav），path 忽略
 *  - SMB   : host 为服务器地址，port 默认 445，path 为共享名（如 volume1）
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
    fun createClient(): IStorageClient {
        return when (type) {
            StorageType.WEBDAV -> WebDavClient(
                baseUrl = host.trim().trimEnd('/'),
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
                basePath = path.trim().ifEmpty { "/" }
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
    }
}
