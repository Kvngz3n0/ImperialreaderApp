package com.imperialreader.data.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packs web-novel chapters into a standard EPUB 2 file (zip, OPF, NCX) that the built-in reader (and others) can open. */
object EpubBuilder {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun cleanBody(html: String): String {
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script, style, iframe, noscript").remove()
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml)
        return doc.body().html()
    }

    fun build(title: String, author: String?, chapters: List<Pair<String, String>>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            // the mimetype entry must be first and stored uncompressed
            val mime = "application/epub+zip".toByteArray()
            val crc = CRC32().apply { update(mime) }
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                this.crc = crc.value
            })
            zip.write(mime)
            zip.closeEntry()

            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
            )

            val uid = "urn:uuid:" + UUID.randomUUID()
            val manifest = StringBuilder("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
            val spine = StringBuilder()
            val navPoints = StringBuilder()

            chapters.forEachIndexed { i, (chTitle, html) ->
                val file = "chapter_${i + 1}.html"
                put(
                    "OEBPS/$file",
                    """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><meta http-equiv="Content-Type" content="text/html; charset=utf-8"/><title>${esc(chTitle)}</title></head><body><h2>${esc(chTitle)}</h2>${cleanBody(html)}</body></html>"""
                )
                manifest.append("""<item id="c${i + 1}" href="$file" media-type="application/xhtml+xml"/>""")
                spine.append("""<itemref idref="c${i + 1}"/>""")
                navPoints.append(
                    """<navPoint id="n${i + 1}" playOrder="${i + 1}"><navLabel><text>${esc(chTitle)}</text></navLabel><content src="$file"/></navPoint>"""
                )
            }

            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${esc(title)}</dc:title><dc:creator>${esc(author ?: "Unknown")}</dc:creator><dc:language>en</dc:language><dc:identifier id="uid">$uid</dc:identifier></metadata><manifest>$manifest</manifest><spine toc="ncx">$spine</spine></package>"""
            )
            put(
                "OEBPS/toc.ncx",
                """<?xml version="1.0" encoding="utf-8"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="$uid"/></head><docTitle><text>${esc(title)}</text></docTitle><navMap>$navPoints</navMap></ncx>"""
            )
        }
    }
}
