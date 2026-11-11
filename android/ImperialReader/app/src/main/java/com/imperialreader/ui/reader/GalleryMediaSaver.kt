package com.imperialreader.ui.reader

import android.content.Context
import android.net.Uri
import com.imperialreader.util.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

object GalleryMediaSaver {
    suspend fun save(context: Context, source: String, headers: Map<String, String>, destination: Uri) =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(source)
            if (uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true)) {
                throw IOException("Streaming playlists cannot be saved as a single video file")
            }
            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: throw IOException("The selected file cannot be opened")
            output.use { sink ->
                when (uri.scheme?.lowercase()) {
                    "http", "https" -> {
                        val request = Request.Builder().url(source).apply {
                            headers.forEach { (key, value) -> header(key, value) }
                        }.build()
                        context.app.network.client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
                            val body = response.body ?: throw IOException("Download response was empty")
                            body.byteStream().use { it.copyTo(sink) }
                        }
                    }
                    "content", "file" -> {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IOException("The media file is unavailable")
                        input.use { it.copyTo(sink) }
                    }
                    else -> throw IOException("Unsupported media URL")
                }
            }
        }
}
