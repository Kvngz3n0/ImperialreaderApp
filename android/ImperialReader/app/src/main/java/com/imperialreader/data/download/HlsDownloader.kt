package com.imperialreader.data.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads an HLS (.m3u8) episode into ONE file: picks the best variant of a master playlist, fetches every
 * segment (decrypting AES-128 when the playlist asks for it) and appends them in order. Plain MPEG-TS segments
 * give a .ts file, fragmented-MP4 playlists (EXT-X-MAP) give a .mp4; both play in the anime player.
 */
internal object HlsDownloader {

    fun isHlsUrl(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)

    private class Key(val method: String, val uri: String?, val iv: String?)
    private class Segment(val url: String, val key: Key?, val sequence: Long)

    private val attrRegex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private fun attrs(line: String): Map<String, String> =
        attrRegex.findAll(line).associate { it.groupValues[1] to it.groupValues[2].trim().trim('"') }

    private fun resolve(base: String, ref: String): String =
        runCatching { base.toHttpUrl().resolve(ref)?.toString() }.getOrNull() ?: ref

    suspend fun download(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        openOutput: (extension: String) -> OutputStream,
        beforeSegment: suspend () -> Unit,
        onProgress: (Int) -> Unit
    ) {
        var playlistUrl = url
        var text = fetchBytes(client, playlistUrl, headers).toString(Charsets.UTF_8)
        if (!text.trimStart().startsWith("#EXTM3U")) throw IOException("Not an HLS playlist")
        if (text.contains("#EXT-X-STREAM-INF")) {
            playlistUrl = bestVariant(text, playlistUrl)
            text = fetchBytes(client, playlistUrl, headers).toString(Charsets.UTF_8)
        }

        var initUrl: String? = null
        var key: Key? = null
        var sequence = 0L
        val segments = ArrayList<Segment>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attrs(line.substringAfter(':'))
                    val method = a["METHOD"].orEmpty()
                    key = if (method.equals("NONE", ignoreCase = true)) null else Key(method, a["URI"], a["IV"])
                }
                line.startsWith("#EXT-X-MAP:") -> initUrl = attrs(line.substringAfter(':'))["URI"]?.let { resolve(playlistUrl, it) }
                line.startsWith("#") -> {}
                else -> {
                    segments += Segment(resolve(playlistUrl, line), key, sequence)
                    sequence++
                }
            }
        }
        if (segments.isEmpty()) throw IOException("The playlist has no segments")

        val keyCache = HashMap<String, ByteArray>()
        openOutput(if (initUrl != null) "mp4" else "ts").use { out ->
            initUrl?.let { out.write(fetchBytes(client, it, headers)) }
            segments.forEachIndexed { index, seg ->
                beforeSegment()
                var data = fetchBytes(client, seg.url, headers)
                val k = seg.key
                if (k != null) {
                    if (!k.method.equals("AES-128", ignoreCase = true)) {
                        throw IOException("Unsupported HLS encryption: ${k.method}")
                    }
                    val keyUri = k.uri ?: throw IOException("HLS key without a URI")
                    val keyBytes = keyCache.getOrPut(keyUri) { fetchBytes(client, resolve(playlistUrl, keyUri), headers) }
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes(k.iv, seg.sequence)))
                    data = cipher.doFinal(data)
                }
                out.write(data)
                onProgress((index + 1) * 100 / segments.size)
            }
        }
    }

    private fun bestVariant(master: String, base: String): String {
        val lines = master.lines().map { it.trim() }
        var bestBandwidth = -1L
        var bestUrl: String? = null
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue
            val bandwidth = attrs(lines[i].substringAfter(':'))["BANDWIDTH"]?.toLongOrNull() ?: 0L
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            if (bandwidth > bestBandwidth) {
                bestBandwidth = bandwidth
                bestUrl = resolve(base, uri)
            }
        }
        return bestUrl ?: throw IOException("No playable variant in the master playlist")
    }

    private fun ivBytes(iv: String?, sequence: Long): ByteArray {
        if (iv != null) {
            val hex = iv.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
            return ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        val out = ByteArray(16)
        for (i in 0 until 8) out[15 - i] = (sequence shr (8 * i)).toByte()
        return out
    }

    private suspend fun fetchBytes(client: OkHttpClient, url: String, headers: Map<String, String>): ByteArray {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        resp.body!!.bytes()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                delay(800L * (attempt + 1))
            }
        }
        throw last ?: IOException("Download failed")
    }
}
