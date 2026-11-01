package com.imperialreader.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipException
import java.util.zip.ZipFile

object LocalArtworkPreview {

    fun supports(name: String?): Boolean {
        val extension = name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return extension == "psd" || extension == "clip"
    }

    fun readerPage(context: Context, document: DocumentFile): String {
        val directory = File(context.cacheDir, "local-artwork")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create the local artwork preview folder")
        }
        val key = "${document.uri.hashCode()}_${document.lastModified()}_${document.length()}"
        val target = File(directory, "$key.png")
        if ((!target.exists() || target.length() == 0L) && !extract(context, document, target)) {
            throw IOException("Could not read an embedded preview from ${document.name}")
        }
        return Uri.fromFile(target).toString()
    }

    fun extract(context: Context, document: DocumentFile, target: File): Boolean {
        val stageDirectory = File(context.cacheDir, "local-artwork").apply {
            if (!exists() && !mkdirs()) throw IOException("Could not create the local artwork preview folder")
        }
        val staged = File.createTempFile("artwork-", ".bin", stageDirectory)
        try {
            val input = context.contentResolver.openInputStream(document.uri)
                ?: throw IOException("Cannot open ${document.name}")
            input.use { source -> staged.outputStream().buffered().use { source.copyTo(it) } }

            if (document.name.orEmpty().endsWith(".clip", true) && extractClipZipPreview(staged, target)) {
                return true
            }
            if (document.name.orEmpty().endsWith(".psd", true) && extractPsdThumbnail(staged, target)) {
                return true
            }
            return extractEmbeddedImage(staged, target)
        } finally {
            staged.delete()
        }
    }

    private fun extractClipZipPreview(file: File, target: File): Boolean {
        return try {
            ZipFile(file).use { zip ->
                val candidates = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.substringAfterLast('/').lowercase() in PREVIEW_NAMES }
                    .sortedBy { if (it.name.substringAfterLast('/').equals("mergedimage.png", true)) 0 else 1 }
                    .toList()
                for (entry in candidates) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                    }
                    val bitmap = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
                        ?: continue
                    save(bitmap, target)
                    return true
                }
                false
            }
        } catch (_: ZipException) {
            false
        }
    }

    private fun extractPsdThumbnail(file: File, target: File): Boolean {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 30) return false
            val signature = ByteArray(4)
            input.readFully(signature)
            if (String(signature, Charsets.US_ASCII) != "8BPS") return false

            input.seek(26)
            val colorModeLength = readUInt32(input)
            input.seek(input.filePointer + colorModeLength)
            val resourcesLength = readUInt32(input)
            val end = input.filePointer + resourcesLength
            while (input.filePointer + 12 <= end) {
                input.readFully(signature)
                if (String(signature, Charsets.US_ASCII) != "8BIM") break
                val resourceId = input.readUnsignedShort()
                val nameLength = input.readUnsignedByte()
                input.seek(input.filePointer + nameLength + ((1 + nameLength) % 2))
                val dataLength = readUInt32(input)
                val dataStart = input.filePointer
                if (resourceId in THUMBNAIL_RESOURCE_IDS && dataLength >= THUMBNAIL_HEADER_SIZE) {
                    val format = readUInt32(input)
                    input.seek(dataStart + 20)
                    val compressedLength = readUInt32(input)
                    if (format == JPEG_THUMBNAIL_FORMAT &&
                        compressedLength in 1..MAX_THUMBNAIL_BYTES &&
                        compressedLength <= dataLength - THUMBNAIL_HEADER_SIZE
                    ) {
                        input.seek(dataStart + THUMBNAIL_HEADER_SIZE)
                        val jpeg = ByteArray(compressedLength.toInt())
                        input.readFully(jpeg)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                            val options = BitmapFactory.Options().apply {
                                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                            }
                            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
                            if (bitmap != null) {
                                save(bitmap, target)
                                return true
                            }
                        }
                    }
                }
                input.seek(dataStart + dataLength + (dataLength % 2))
            }
        }
        return false
    }

    private fun extractEmbeddedImage(file: File, target: File): Boolean {
        val offsets = imageOffsets(file)
        val best = offsets.mapNotNull { offset ->
            val bounds = decodeBounds(file, offset) ?: return@mapNotNull null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
            else offset to bounds
        }.maxByOrNull { (_, bounds) -> bounds.outWidth.toLong() * bounds.outHeight }
            ?: return false
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(best.second.outWidth, best.second.outHeight)
        }
        val bitmap = decode(file, best.first, options) ?: return false
        save(bitmap, target)
        return true
    }

    private fun imageOffsets(file: File): List<Long> {
        val found = LinkedHashSet<Long>()
        val buffer = ByteArray(SCAN_CHUNK + 11)
        var carry = 0
        var consumed = 0L
        RandomAccessFile(file, "r").use { input ->
            while (found.size < MAX_IMAGE_CANDIDATES) {
                val count = input.read(buffer, carry, SCAN_CHUNK)
                if (count < 0) break
                val total = carry + count
                val base = consumed - carry
                for (index in 0..(total - 3).coerceAtLeast(-1)) {
                    if (matchesPng(buffer, index, total) || matchesJpeg(buffer, index, total) ||
                        matchesWebp(buffer, index, total)
                    ) {
                        found += base + index
                        if (found.size >= MAX_IMAGE_CANDIDATES) break
                    }
                }
                consumed += count
                carry = minOf(11, total)
                System.arraycopy(buffer, total - carry, buffer, 0, carry)
            }
        }
        return found.toList()
    }

    private fun matchesPng(data: ByteArray, offset: Int, limit: Int): Boolean =
        offset + 8 <= limit &&
            data[offset] == 0x89.toByte() && data[offset + 1] == 0x50.toByte() &&
            data[offset + 2] == 0x4e.toByte() && data[offset + 3] == 0x47.toByte() &&
            data[offset + 4] == 0x0d.toByte() && data[offset + 5] == 0x0a.toByte() &&
            data[offset + 6] == 0x1a.toByte() && data[offset + 7] == 0x0a.toByte()

    private fun matchesJpeg(data: ByteArray, offset: Int, limit: Int): Boolean =
        offset + 3 <= limit &&
            data[offset] == 0xff.toByte() && data[offset + 1] == 0xd8.toByte() &&
            data[offset + 2] == 0xff.toByte()

    private fun matchesWebp(data: ByteArray, offset: Int, limit: Int): Boolean =
        offset + 12 <= limit &&
            String(data, offset, 4, Charsets.US_ASCII) == "RIFF" &&
            String(data, offset + 8, 4, Charsets.US_ASCII) == "WEBP"

    private fun decodeBounds(file: File, offset: Long): BitmapFactory.Options? =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            openAt(file, offset).use { BitmapFactory.decodeStream(it, null, this) }
        }

    private fun decode(file: File, offset: Long, options: BitmapFactory.Options): Bitmap? =
        openAt(file, offset).use { BitmapFactory.decodeStream(it, null, options) }

    private fun openAt(file: File, offset: Long): BufferedInputStream {
        val input = BufferedInputStream(FileInputStream(file))
        var remaining = offset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) {
                    input.close()
                    throw IOException("Could not read the artwork preview")
                }
                remaining--
            } else {
                remaining -= skipped
            }
        }
        return input
    }

    private fun save(bitmap: Bitmap, target: File) {
        try {
            target.outputStream().use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    throw IOException("Could not save the artwork preview")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_PREVIEW_DIMENSION || height / sample > MAX_PREVIEW_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    private fun readUInt32(input: RandomAccessFile): Long = input.readInt().toLong() and 0xffffffffL

    private const val SCAN_CHUNK = 64 * 1024
    private const val MAX_IMAGE_CANDIDATES = 64
    private const val MAX_PREVIEW_DIMENSION = 2400
    private const val THUMBNAIL_HEADER_SIZE = 28L
    private const val MAX_THUMBNAIL_BYTES = 64L * 1024L * 1024L
    private const val JPEG_THUMBNAIL_FORMAT = 1L
    private val THUMBNAIL_RESOURCE_IDS = setOf(1033, 1036)
    private val PREVIEW_NAMES = setOf("mergedimage.png", "thumbnail.png")
}
