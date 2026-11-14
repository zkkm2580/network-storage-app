package com.example.networkstorage.client

import com.example.networkstorage.model.RemoteFile
import java.io.InputStream

/**
 * 所有网络存储协议的统一抽象。实现类全部为同步阻塞式，
 * 调用方必须在后台线程中使用（见 ui 包中的 Thread + runOnUiThread 用法）。
 */
interface IStorageClient {

    /** 建立连接并做一次轻量探测，失败返回 false。 */
    fun connect(): Boolean

    /** 列出目录内容，path 使用 "/" 分隔的绝对路径，根目录传 "/"。 */
    fun listFiles(path: String): List<RemoteFile>

    /** 打开远程文件用于读取，调用方负责关闭。 */
    fun openInputStream(path: String): InputStream

    /** 上传一个文件，覆盖同名文件。 */
    fun upload(path: String, data: ByteArray): Boolean

    /** 新建目录。 */
    fun createDirectory(path: String): Boolean

    /** 删除文件或目录（目录递归删除）。 */
    fun delete(path: String): Boolean

    /** 释放连接资源。 */
    fun disconnect()
}

/** 统一的网络存储异常，UI 层可以直接展示 message。 */
class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)
