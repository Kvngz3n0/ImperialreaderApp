package com.imperialreader.data.backup

import android.content.Context
import android.net.Uri
import com.imperialreader.data.db.CategoryEntity
import com.imperialreader.data.db.ChapterEntity
import com.imperialreader.data.db.HistoryEntity
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.util.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * JSON backup of the library: entries, categories, chapters (read / bookmark / progress) and history.
 * Restoring merges into what you already have (nothing is deleted, read state is never lost).
 */
class BackupManager(private val ctx: Context) {

    private val db get() = ctx.app.db
    private val backupMutex get() = automaticBackupMutex

    companion object {
        private const val AUTO_PREFIX = "imperial_reader_auto_"
        private const val AUTO_LIMIT = 5
        private val automaticBackupMutex = Mutex()
    }

    /**
     * Creates a complete temporary JSON backup, promotes it to the rolling backup set, then
     * removes the oldest automatic backups. User-created manual exports are never pruned.
     */
    suspend fun createAutomaticBackup(): Int = backupMutex.withLock {
        withContext(Dispatchers.IO) {
            val dir = ctx.app.storage.automaticBackupDir()
            val stamp = System.currentTimeMillis()
            val finalName = "$AUTO_PREFIX$stamp.json"
            val temporary = dir.createFile("application/json", "$finalName.part")
                ?: throw IOException("Cannot create an automatic backup file")
            var promoted = false
            try {
                val entryCount = export(temporary.uri)
                if (!temporary.renameTo(finalName)) {
                    throw IOException("Could not finish the automatic backup file")
                }
                promoted = true
                pruneAutomaticBackups(dir)
                entryCount
            } catch (e: Exception) {
                if (!promoted) temporary.delete()
                throw e
            }
        }
    }

    private fun pruneAutomaticBackups(dir: androidx.documentfile.provider.DocumentFile) {
        val backups = dir.listFiles()
            .filter { !it.isDirectory && it.name.orEmpty().startsWith(AUTO_PREFIX) && it.name.orEmpty().endsWith(".json") }
            .sortedWith(
                compareBy<androidx.documentfile.provider.DocumentFile> {
                    it.lastModified().takeIf { modified -> modified > 0L }
                        ?: it.name.orEmpty().removePrefix(AUTO_PREFIX).removeSuffix(".json").toLongOrNull()
                        ?: 0L
                }.thenBy { it.name.orEmpty() }
            )
        val excess = (backups.size - AUTO_LIMIT).coerceAtLeast(0)
        backups.take(excess).forEach { old ->
            if (!old.delete() && old.exists()) {
                throw IOException("Could not remove an old automatic backup")
            }
        }
    }

    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val categories = db.categoryDao().getAll()
        val manga = db.mangaDao().getFavoritesList()
        val root = JSONObject().put("version", 1).put("exported", System.currentTimeMillis())

        root.put("categories", JSONArray().also { arr ->
            categories.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("order", it.sortOrder)) }
        })

        val history = HashMap<Long, Long>()
        val mangaArr = JSONArray()
        for (m in manga) {
            val chapters = db.chapterDao().getAll(m.id)
            val chArr = JSONArray()
            chapters.forEach { c ->
                chArr.put(
                    JSONObject().put("url", c.url).put("name", c.name).put("scanlator", c.scanlator)
                        .put("dateUpload", c.dateUpload).put("number", c.chapterNumber.toDouble())
                        .put("order", c.sourceOrder).put("read", c.read).put("bookmark", c.bookmark)
                        .put("page", c.lastPageRead)
                )
            }
            mangaArr.put(
                JSONObject().put("source", m.source).put("url", m.url).put("title", m.title)
                    .put("author", m.author).put("artist", m.artist).put("description", m.description)
                    .put("genre", m.genre).put("status", m.status).put("cover", m.coverUrl)
                    .put("dateAdded", m.dateAdded).put("category", categories.firstOrNull { it.id == m.categoryId }?.name)
                    .put("chapters", chArr)
            )
        }
        root.put("manga", mangaArr)

        val historyArr = JSONArray()
        db.historyDao().all().forEach { h ->
            val ch = db.chapterDao().getById(h.chapterId) ?: return@forEach
            val m = db.mangaDao().getById(ch.mangaId) ?: return@forEach
            historyArr.put(JSONObject().put("source", m.source).put("mangaUrl", m.url).put("chapterUrl", ch.url).put("lastRead", h.lastRead))
        }
        root.put("history", historyArr)

        val out = ctx.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Cannot write the backup file")
        out.use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
        manga.size
    }

    /** Returns the number of library entries restored. */
    suspend fun restore(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Cannot read the backup file")
        val root = JSONObject(text)
        if (!root.has("manga")) throw IOException("This is not an Imperial Reader backup")

        val catDao = db.categoryDao()
        val mangaDao = db.mangaDao()
        val chapterDao = db.chapterDao()

        // categories by name
        val categoryByName = catDao.getAll().associateBy { it.name }.toMutableMap()
        root.optJSONArray("categories")?.let { arr ->
            for (i in 0 until arr.length()) {
                val name = arr.getJSONObject(i).getString("name")
                if (name !in categoryByName) {
                    val id = catDao.insert(CategoryEntity(name = name, sortOrder = arr.getJSONObject(i).optInt("order")))
                    categoryByName[name] = CategoryEntity(id = id, name = name)
                }
            }
        }

        val mangaArr = root.getJSONArray("manga")
        var restored = 0
        for (i in 0 until mangaArr.length()) {
            val o = mangaArr.getJSONObject(i)
            val source = o.getString("source")
            val url = o.getString("url")
            val categoryId = o.optString("category").takeIf { it.isNotBlank() && it != "null" }?.let { categoryByName[it]?.id } ?: 0L

            val existing = mangaDao.find(source, url)
            val mangaId: Long
            if (existing == null) {
                mangaDao.insert(
                    MangaEntity(
                        source = source, url = url, title = o.getString("title"),
                        author = o.optString("author").takeIf { it != "null" && it.isNotBlank() },
                        artist = o.optString("artist").takeIf { it != "null" && it.isNotBlank() },
                        description = o.optString("description").takeIf { it != "null" && it.isNotBlank() },
                        genre = o.optString("genre").takeIf { it != "null" && it.isNotBlank() },
                        status = o.optInt("status"),
                        coverUrl = o.optString("cover").takeIf { it != "null" && it.isNotBlank() },
                        favorite = true, dateAdded = o.optLong("dateAdded"), initialized = true, categoryId = categoryId
                    )
                )
                mangaId = mangaDao.find(source, url)!!.id
            } else {
                mangaDao.update(existing.copy(favorite = true, categoryId = categoryId, initialized = true))
                mangaId = existing.id
            }

            val have = chapterDao.getAll(mangaId).associateBy { it.url }
            val chArr = o.optJSONArray("chapters") ?: JSONArray()
            val toInsert = mutableListOf<ChapterEntity>()
            for (j in 0 until chArr.length()) {
                val c = chArr.getJSONObject(j)
                val old = have[c.getString("url")]
                if (old == null) {
                    toInsert.add(
                        ChapterEntity(
                            mangaId = mangaId, url = c.getString("url"), name = c.getString("name"),
                            scanlator = c.optString("scanlator").takeIf { it != "null" && it.isNotBlank() },
                            dateUpload = c.optLong("dateUpload"), chapterNumber = c.optDouble("number", -1.0).toFloat(),
                            sourceOrder = c.optInt("order"), read = c.optBoolean("read"),
                            bookmark = c.optBoolean("bookmark"), lastPageRead = c.optInt("page")
                        )
                    )
                } else {
                    chapterDao.update(
                        old.copy(
                            read = old.read || c.optBoolean("read"), bookmark = old.bookmark || c.optBoolean("bookmark"),
                            lastPageRead = maxOf(old.lastPageRead, c.optInt("page"))
                        )
                    )
                }
            }
            if (toInsert.isNotEmpty()) chapterDao.insertAll(toInsert)
            restored++
        }

        root.optJSONArray("history")?.let { arr ->
            for (i in 0 until arr.length()) {
                val h = arr.getJSONObject(i)
                val m = mangaDao.find(h.getString("source"), h.getString("mangaUrl")) ?: continue
                val ch = chapterDao.getAll(m.id).firstOrNull { it.url == h.getString("chapterUrl") } ?: continue
                db.historyDao().upsert(HistoryEntity(chapterId = ch.id, lastRead = h.optLong("lastRead")))
            }
        }
        restored
    }
}
