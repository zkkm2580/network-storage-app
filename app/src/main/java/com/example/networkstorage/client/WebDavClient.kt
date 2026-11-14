package com.example.networkstorage.client

import com.example.networkstorage.model.RemoteFile
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * WebDAV 客户端（基于 OkHttp + 标准 WebDAV 方法 PROPFIND/MKCOL/PUT/DELETE）。
 *
 * @param baseUrl 形如 https://dav.example.com/dav 的基础地址，可带端口
 * @param username Basic 认证用户名
 * @param password Basic 认证密码
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String
) : IStorageClient {

    private val root: String = baseUrl.trimEnd('/')

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val propfindBody: String =
        """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:resourcetype/>
    <D:getcontentlength/>
    <D:getlastmodified/>
  </D:prop>
</D:propfind>"""

    override fun connect(): Boolean {
        return try {
            propfind("/")
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun listFiles(path: String): List<RemoteFile> {
        val requested = normalize(path)
        val xml = propfind(requested)
        val wantedPath = serverPath(requested).trimEnd('/')
        val out = ArrayList<RemoteFile>()

        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))

        val responses = doc.getElementsByTagNameNS("*", "response")
        for (i in 0 until responses.length) {
            val resp = responses.item(i) as? Element ?: continue
            val href = firstText(resp, "href") ?: continue
            val decoded = decodePercent(stripTrailingSlash(serverPathOf(href)))
            if (decoded == wantedPath || decoded.isEmpty()) continue

            val name = decoded.substringAfterLast('/')
            if (name.isEmpty()) continue

            val isDir = resp.getElementsByTagNameNS("*", "collection").length > 0
            val size = firstText(resp, "getcontentlength")?.toLongOrNull() ?: 0L
            val modified = parseHttpDate(firstText(resp, "getlastmodified"))

            out.add(
                RemoteFile(
                    name = name,
                    path = joinPath(requested, name),
                    size = size,
                    isDirectory = isDir,
                    lastModified = modified
                )
            )
        }
        out.sortWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) })
        return out
    }

    override fun openInputStream(path: String): InputStream {
        val req = auth(Request.Builder().url(urlOf(path)).get().build())
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw StorageException("WebDAV 下载失败: HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            return ByteArrayInputStream(bytes)
        }
    }

    override fun upload(path: String, data: ByteArray): Boolean {
        val req = auth(
            Request.Builder()
                .url(urlOf(path))
                .put(data.toRequestBody("application/octet-stream".toMediaType()))
                .build()
        )
        http.newCall(req).execute().use { return it.isSuccessful }
    }

    override fun createDirectory(path: String): Boolean {
        val req = auth(Request.Builder().url(urlOf(path)).method("MKCOL", null).build())
        http.newCall(req).execute().use { return it.isSuccessful || it.code == 405 }
    }

    override fun delete(path: String): Boolean {
        val req = auth(Request.Builder().url(urlOf(path)).delete().build())
        http.newCall(req).execute().use { return it.isSuccessful }
    }

    override fun disconnect() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    // ---------------------------------------------------------------- helpers

    private fun propfind(path: String): String {
        val req = auth(
            Request.Builder()
                .url(urlOf(path))
                .method("PROPFIND", propfindBody.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", "1")
                .build()
        )
        http.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299 && resp.code != 207) {
                throw StorageException("WebDAV PROPFIND 失败: HTTP ${resp.code}")
            }
            return resp.body?.string() ?: throw StorageException("WebDAV 返回空响应体")
        }
    }

    private fun auth(b: Request.Builder): Request {
        if (username.isNotEmpty()) {
            b.header("Authorization", Credentials.basic(username, password))
        }
        return b.build()
    }

    private fun urlOf(path: String): String {
        val n = normalize(path)
        val suffix = if (n == "/") "" else encodePath(n)
        return root + suffix
    }

    private fun serverPath(path: String): String {
        val n = normalize(path)
        val base = URI(root).path ?: ""
        val baseNorm = if (base.isEmpty()) "" else "/" + base.trim('/')
        return if (n == "/") baseNorm.ifEmpty { "/" } else baseNorm + n
    }

    private fun serverPathOf(href: String): String {
        val trimmed = href.trim()
        return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            URI(trimmed).path ?: "/"
        } else {
            if (trimmed.startsWith("?")) "/" else trimmed.substringBefore('?')
        }
    }

    private fun firstText(parent: Element, localName: String): String? {
        val list = parent.getElementsByTagNameNS("*", localName)
        if (list.length == 0) return null
        return list.item(0)?.textContent?.trim()?.ifEmpty { null }
    }

    private companion object {
        fun normalize(p: String): String {
            val t = p.trim().replace('\\', '/')
            val noTail = t.trimEnd('/')
            return if (noTail.isEmpty()) "/" else noTail
        }

        fun joinPath(parent: String, name: String): String {
            val p = normalize(parent)
            return if (p == "/") "/$name" else "$p/$name"
        }

        fun stripTrailingSlash(s: String): String {
            val t = s.trimEnd('/')
            return if (t.isEmpty()) "/" else t
        }

        fun encodePath(path: String): String {
            val parts = path.split('/')
            val sb = StringBuilder()
            for (part in parts) {
                if (part.isEmpty()) continue
                sb.append('/')
                sb.append(
                    URLEncoder.encode(part, "UTF-8")
                        .replace("+", "%20")
                        .replace("%2F", "/")
                )
            }
            return if (sb.isEmpty()) "" else sb.toString()
        }

        fun decodePercent(s: String): String {
            return try {
                URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                s
            }
        }

        fun parseHttpDate(raw: String?): Long {
            if (raw.isNullOrEmpty()) return 0L
            val formats = listOf(
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEE, dd MMM yyyy HH:mm:ss Z",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
            )
            for (f in formats) {
                try {
                    val sdf = java.text.SimpleDateFormat(f, Locale.US)
                    sdf.isLenient = true
                    return sdf.parse(raw)?.time ?: 0L
                } catch (_: Exception) {
                    // 尝试下一种格式
                }
            }
            return 0L
        }
    }
}
