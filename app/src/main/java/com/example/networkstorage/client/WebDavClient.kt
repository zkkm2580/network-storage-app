package com.example.networkstorage.client

import com.example.networkstorage.model.RemoteFile
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV 客户端（OkHttp + PROPFIND/MKCOL/PUT/DELETE）。
 * 仅支持 HTTPS：明文 HTTP 下 Basic 凭据可被嗅探，已由网络安全策略禁止。
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

    private val propfindBody: String = """<?xml version='1.0' encoding='utf-8'?>
<D:propfind xmlns:D='DAV:'>
  <D:prop>
    <D:resourcetype/>
    <D:getcontentlength/>
    <D:getlastmodified/>
  </D:prop>
</D:propfind>"""

    override fun connect(): Boolean = try {
        propfind("/")
        true
    } catch (e: Exception) {
        false
    }

    override fun listFiles(path: String): List<RemoteFile> {
        val requested = normalize(path)
        val xml = propfind(requested)
        val wantedPath = stripTrailingSlash(serverPath(requested))
        val out = ArrayList<RemoteFile>()

        val doc = newSecureDocumentBuilder().parse(InputSource(StringReader(xml)))

        val responses = doc.getElementsByTagNameNS("*", "response")
        for (i in 0 until responses.length) {
            val resp = responses.item(i) as? Element ?: continue
            val href = firstText(resp, "href") ?: continue
            val decoded = decodePercent(stripTrailingSlash(serverPathOf(href)))
            if (decoded == wantedPath || decoded.isEmpty()) continue

            val name = decoded.substringAfterLast('/')
            if (!isSafeEntryName(name)) continue

            val isDir = resp.getElementsByTagNameNS("*", "collection").length > 0
            val size = firstText(resp, "getcontentlength")?.toLongOrNull() ?: 0L
            val modified = parseHttpDate(firstText(resp, "getlastmodified"))

            out.add(
                RemoteFile(
                    name = name,
                    path = joinRemotePath(requested, name),
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
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw StorageException("WebDAV 下载失败: HTTP ${resp.code}")
        }
        val body = resp.body
        if (body == null) {
            resp.close()
            return ByteArrayInputStream(ByteArray(0))
        }
        return object : FilterInputStream(body.byteStream()) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    resp.close()
                }
            }
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
        // 资源已存在等价于成功；避免把服务端「不支持 MKCOL」的 405 误判为成功
        try {
            propfind(path)
            return true
        } catch (ignored: Exception) {
        }
        val req = auth(Request.Builder().url(urlOf(path)).method("MKCOL", null).build())
        http.newCall(req).execute().use { return it.isSuccessful }
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
        if (root.startsWith("http://", true)) {
            throw StorageException("仅支持 HTTPS：明文 HTTP 会泄露 Basic 凭据")
        }
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

    private fun auth(request: Request): Request {
        if (username.isEmpty()) return request
        return request.newBuilder()
            .header("Authorization", Credentials.basic(username, password))
            .build()
    }

    private fun urlOf(path: String): String {
        val n = normalize(path)
        val suffix = if (n == "/") "" else encodePath(n)
        return root + suffix
    }

    private fun serverPath(path: String): String {
        val n = normalize(path)
        val base = try {
            URI(root).path ?: ""
        } catch (e: Exception) {
            ""
        }
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
        /** 解析远端返回的 XML 时禁用 DOCTYPE 与外部实体，避免 XXE。 */
        fun newSecureDocumentBuilder(): DocumentBuilder {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                try {
                    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                } catch (ignored: Exception) {
                }
                try {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                } catch (ignored: Exception) {
                }
                try {
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                } catch (ignored: Exception) {
                }
                try {
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                } catch (ignored: Exception) {
                }
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            return factory.newDocumentBuilder()
        }

        fun normalize(p: String): String {
            val t = p.trim().replace('\\', '/')
            val noTail = t.trimEnd('/')
            return if (noTail.isEmpty()) "/" else noTail
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

        fun decodePercent(s: String): String = try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            s
        }

        fun parseHttpDate(raw: String?): Long {
            if (raw.isNullOrEmpty()) return 0L
            val formats = listOf(
                "EEE, dd MMM yyyy HH:mm:ss zzz" to false,
                "EEE, dd MMM yyyy HH:mm:ss Z" to false,
                "yyyy-MM-dd'T'HH:mm:ss'Z'" to true
            )
            for ((f, utc) in formats) {
                try {
                    val sdf = java.text.SimpleDateFormat(f, Locale.US)
                    sdf.isLenient = true
                    if (utc) sdf.timeZone = TimeZone.getTimeZone("UTC")
                    return sdf.parse(raw)?.time ?: 0L
                } catch (ignored: Exception) {
                }
            }
            return 0L
        }
    }
}
