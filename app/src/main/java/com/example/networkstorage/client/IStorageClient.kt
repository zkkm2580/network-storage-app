package com.example.networkstorage.client

import com.example.networkstorage.model.RemoteFile
import java.io.InputStream

/**
 * 所有网络存储协议的统一抽象。实现类全部为同步阻塞式，
 * 调用方必须在后台线程中使用，并串行化同一实例的调用。
 */
interface IStorageClient {

    /** 建立连接并做一次轻量探测，失败返回 false。 */
    fun connect(): Boolean

    /** 列出目录内容，path 使用 "/" 分隔的绝对路径，根目录传 "/"。 */
    fun listFiles(path: String): List<RemoteFile>

    /** 打开远程文件用于流式读取，调用方负责关闭；实现类不得把整个文件读入内存。 */
    fun openInputStream(path: String): InputStream

    /** 上传一个文件，覆盖同名文件。 */
    fun upload(path: String, data: ByteArray): Boolean

    /** 新建目录。 */
    fun createDirectory(path: String): Boolean

    /** 删除文件或目录。 */
    fun delete(path: String): Boolean

    /** 释放连接资源。 */
    fun disconnect()
}

/** 统一的网络存储异常，UI 层可以直接展示 message。 */
class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 过滤来自远端的文件名，避免路径穿越与名称注入。 */
fun isSafeEntryName(name: String): Boolean {
    if (name.isEmpty() || name == "." || name == "..") return false
    if (name.contains('/') || name.contains('\\')) return false
    return true
}

/** 把服务端返回的条目名拼到当前目录后，得到 UI 侧使用的绝对路径。 */
fun joinRemotePath(parent: String, name: String): String {
    if (!isSafeEntryName(name)) throw StorageException("非法文件名: $name")
    val p = parent.trim().replace('\\', '/').trimEnd('/')
    return if (p.isEmpty()) "/$name" else "$p/$name"
}
