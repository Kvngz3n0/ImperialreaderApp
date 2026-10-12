package com.imperialreader.data.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.imperialreader.util.NaturalOrder
import com.imperialreader.util.isImageName
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** CBZ/ZIP and CBR/RAR (RAR 2.x–4.x) reading. The real format is detected from the file header, not the extension. */
object Archives {

    enum class Kind { ZIP, RAR, UNKNOWN }

    fun kind(ctx: Context, doc: DocumentFile): Kind {
        val head = ByteArray(8)
        val read = runCatching {
            ctx.contentResolver.openInputStream(doc.uri)?.use { it.read(head) } ?: -1
        }.getOrDefault(-1)
        if (read >= 4) {
            if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return Kind.ZIP
            if (head[0] == 'R'.code.toByte() && head[1] == 'a'.code.toByte() && head[2] == 'r'.code.toByte() && head[3] == '!'.code.toByte()) {
                if (read >= 7 && head[6] == 1.toByte()) {
                    throw IOException("This is a RAR5 archive, which can't be read yet. Re-save it as CBZ (or RAR4) and rescan.")
                }
                return Kind.RAR
            }
        }
        val n = doc.name.orEmpty().lowercase()
        return when {
            n.endsWith(".cbz") || n.endsWith(".zip") -> Kind.ZIP
            n.endsWith(".cbr") || n.endsWith(".rar") -> Kind.RAR
            else -> Kind.UNKNOWN
        }
    }

    private fun flat(name: String) = name.replace('/', '_').replace('\\', '_').replace("..", "_")

    private fun skip(name: String) = !isImageName(name) || name.contains("__MACOSX") || name.substringAfterLast('/').substringAfterLast('\\').startsWith(".")

    /** Extracts every page image into [dir] (names flattened; sort naturally afterwards). */
    fun extractImages(ctx: Context, doc: DocumentFile, dir: File) {
        when (kind(ctx, doc)) {
            Kind.ZIP -> openStream(ctx, doc).use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && !skip(entry.name)) File(dir, flat(entry.name)).outputStream().use { zip.copyTo(it) }
                        entry = zip.nextEntry
                    }
                }
            }
            Kind.RAR -> withRar(ctx, doc) { archive ->
                for (h in imageHeaders(archive)) File(dir, flat(h.fileName)).outputStream().use { archive.extractFile(h, it) }
            }
            Kind.UNKNOWN -> throw IOException("Unsupported archive type")
        }
    }

    /** Writes the first page image to [target]. Returns false if none was found. */
    fun extractFirstImage(ctx: Context, doc: DocumentFile, target: File): Boolean {
        var found = false
        when (kind(ctx, doc)) {
            Kind.ZIP -> openStream(ctx, doc).use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null && !found) {
                        if (!entry.isDirectory && !skip(entry.name)) {
                            target.outputStream().use { zip.copyTo(it) }
                            found = true
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            Kind.RAR -> withRar(ctx, doc) { archive ->
                imageHeaders(archive).firstOrNull()?.let {
                    target.outputStream().use { out -> archive.extractFile(it, out) }
                    found = true
                }
            }
            Kind.UNKNOWN -> {}
        }
        return found
    }

    private fun imageHeaders(archive: Archive): List<FileHeader> =
        archive.fileHeaders.filter { !it.isDirectory && !skip(it.fileName) }
            .sortedWith(compareBy(NaturalOrder) { it.fileName })

    private fun openStream(ctx: Context, doc: DocumentFile) =
        ctx.contentResolver.openInputStream(doc.uri) ?: throw IOException("Cannot open ${doc.name}")

    private fun withRar(ctx: Context, doc: DocumentFile, block: (Archive) -> Unit) {
        try {
            openStream(ctx, doc).use { raw -> Archive(raw).use { block(it) } }
        } catch (e: com.github.junrar.exception.RarException) {
            throw IOException("Can't read this RAR file (${e.message}). Re-save it as CBZ and rescan.")
        }
    }
}
