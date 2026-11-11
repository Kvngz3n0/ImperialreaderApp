package com.imperialreader.data.download

import org.json.JSONArray
import org.json.JSONObject

internal data class PersistedDownloadTask(
    val mangaId: Long,
    val chapterId: Long,
    val dub: Boolean? = null,
    val failure: String? = null
)

internal fun encodeDownloadQueueSnapshot(tasks: List<PersistedDownloadTask>): String =
    JSONObject().put("version", 1).put("tasks", JSONArray().also { array ->
        tasks.forEach { task ->
            array.put(JSONObject()
                .put("mangaId", task.mangaId)
                .put("chapterId", task.chapterId)
                .put("dub", task.dub ?: JSONObject.NULL)
                .put("failure", task.failure ?: JSONObject.NULL))
        }
    }).toString()

internal fun decodeDownloadQueueSnapshot(json: String?): List<PersistedDownloadTask> {
    if (json.isNullOrBlank()) return emptyList()
    val tasks = JSONObject(json).optJSONArray("tasks") ?: return emptyList()
    return buildList {
        for (index in 0 until tasks.length()) {
            val item = tasks.optJSONObject(index) ?: continue
            val mangaId = item.optLong("mangaId", 0L).takeIf { it > 0L } ?: continue
            val chapterId = item.optLong("chapterId", 0L).takeIf { it > 0L } ?: continue
            val dub = if (item.isNull("dub")) null else item.optBoolean("dub")
            val failure = item.optString("failure").takeUnless { it.isBlank() || it == "null" }
            add(PersistedDownloadTask(mangaId, chapterId, dub, failure))
        }
    }.distinctBy { it.chapterId }
}