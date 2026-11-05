package com.macedonga.visor.core.assistant.mcp

import android.graphics.BitmapFactory
import android.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A server's icon: the ones it declares, else its website's, else what its own address and the domain above it serve.
 * Only images Android decodes (PNG, JPEG, WebP, GIF, ICO); no credentials go with these requests.
 */
internal class ServerIcons(http: OkHttpClient) {
    private val http = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()

    suspend fun find(server: HttpUrl, info: ServerInfo?): ByteArray? {
        val declared = info?.icons.orEmpty().filterNot { it.mimeType.contains("svg") || it.src.substringBefore('?').endsWith(".svg") }
            .sortedByDescending(::score)
        for (icon in declared) image(icon.src)?.let { return it }
        val sites = (listOfNotNull(info?.website?.toHttpUrlOrNull(), server) + listOfNotNull(parent(server))).distinctBy { it.host }
        for (site in sites) {
            for (href in pageIcons(site)) image(href)?.let { return it }
            site.resolve("/favicon.ico")?.let { url -> image(url.toString())?.let { return it } }
        }
        return null
    }

    /** Dark-theme icons first, since the app is dark, then the largest. */
    private fun score(icon: IconRef): Int {
        val size = icon.sizes.split(' ').maxOfOrNull { s -> if (s == "any") 512 else s.substringBefore('x').toIntOrNull() ?: 0 } ?: 0
        return (if (icon.theme == "dark") 10_000 else 0) + size.coerceAtMost(512)
    }

    /** The icons a site's home page links, the larger touch icons first. */
    private suspend fun pageIcons(site: HttpUrl): List<String> = runCatching {
        val home = site.resolve("/") ?: return emptyList()
        http.newCall(Request.Builder().url(home).header("Accept", "text/html").build()).fetch { r ->
            if (!r.isSuccessful) return@fetch emptyList()
            val html = r.body.source().let { it.request(MAX_PAGE); it.buffer.clone().readUtf8() }
            val base = r.request.url
            LINK.findAll(html).mapNotNull { m ->
                val tag = m.value
                val rel = REL.find(tag)?.groupValues?.get(1)?.lowercase() ?: return@mapNotNull null
                val href = HREF.find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                if ("icon" !in rel || href.substringBefore('?').endsWith(".svg")) return@mapNotNull null
                val url = base.resolve(href.replace("&amp;", "&"))?.toString() ?: return@mapNotNull null
                (if ("apple-touch" in rel) 0 else 1) to url
            }.sortedBy { it.first }.map { it.second }.toList()
        }
    }.getOrDefault(emptyList())

    /** The image at [src], a web address or a data URI, when it is small and decodes. */
    private suspend fun image(src: String): ByteArray? = runCatching {
        val bytes = if (src.startsWith("data:")) {
            Base64.decode(src.substringAfter("base64,", ""), Base64.DEFAULT)
        } else {
            val url = src.toHttpUrlOrNull() ?: return null
            http.newCall(Request.Builder().url(url).header("Accept", "image/*").build()).fetch { r ->
                if (!r.isSuccessful || r.body.contentLength() > MAX_IMAGE) return@fetch null
                r.body.source().let { it.request(MAX_IMAGE + 1); it.buffer.clone().readByteArray() }.takeIf { it.size <= MAX_IMAGE }
            } ?: return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        bytes.takeIf { bounds.outWidth >= MIN_SIDE && bounds.outHeight >= MIN_SIDE }
    }.getOrNull()

    private companion object {
        const val MAX_PAGE = 256L * 1024
        const val MAX_IMAGE = 512L * 1024
        const val MIN_SIDE = 16
        val LINK = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        val REL = Regex("\\brel\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val HREF = Regex("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

        /** mcp.example.com's parent, example.com, where the brand's own icon usually is. */
        fun parent(url: HttpUrl): HttpUrl? {
            val labels = url.host.split('.')
            if (labels.size < 3 || url.host.all { it.isDigit() || it == '.' }) return null
            return url.newBuilder().host(labels.drop(1).joinToString(".")).port(HttpUrl.defaultPort(url.scheme)).encodedPath("/").query(null).build()
        }
    }
}
