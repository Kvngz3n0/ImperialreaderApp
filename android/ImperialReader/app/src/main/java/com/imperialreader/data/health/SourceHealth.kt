package com.imperialreader.data.health

import android.content.Context
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.io.IOException

enum class HealthStatus { UNKNOWN, WORKING, UNAVAILABLE }

data class HealthRecord(
    val status: HealthStatus = HealthStatus.UNKNOWN,
    val lastChecked: Long = 0,
    val lastSuccess: Long = 0,
    val failures: Int = 0,
    val lastError: String? = null
)

internal fun healthAfterCheck(previous: HealthRecord, now: Long, error: String?): HealthRecord =
    if (error == null) previous.copy(status = HealthStatus.WORKING, lastChecked = now, lastSuccess = now, lastError = null)
    else previous.copy(
        status = HealthStatus.UNAVAILABLE,
        lastChecked = now,
        failures = if (previous.failures == Int.MAX_VALUE) Int.MAX_VALUE else previous.failures + 1,
        lastError = error
    )

class SourceHealth(context: Context) {
    private val appContext = context.applicationContext
    private val records = appContext.getSharedPreferences("source_health", Context.MODE_PRIVATE)

    fun enabled(): Boolean =
        PreferenceManager.getDefaultSharedPreferences(appContext).getBoolean("source_health_enabled", false)

    fun read(id: String): HealthRecord = synchronized(lock) {
        val status = when (records.getString("$id.status", null)) {
            "WORKING" -> HealthStatus.WORKING
            "UNAVAILABLE" -> HealthStatus.UNAVAILABLE
            else -> HealthStatus.UNKNOWN
        }
        HealthRecord(
            status,
            records.getLong("$id.checked", 0L),
            records.getLong("$id.success", 0L),
            records.getInt("$id.failures", 0),
            records.getString("$id.error", null)
        )
    }

    suspend fun checkAll(
        probes: List<Pair<String, suspend () -> Boolean>>,
        onResult: (String, HealthRecord) -> Unit
    ) = coroutineScope {
        probes.map { (id, probe) ->
            async {
                limiter.withPermit {
                    if (!enabled()) return@withPermit
                    val prior = read(id)
                    if (System.currentTimeMillis() - prior.lastChecked < CHECK_INTERVAL_MS) {
                        onResult(id, prior)
                        return@withPermit
                    }
                    val error = try {
                        if (!withTimeout(TIMEOUT_MS) { probe() }) throw IOException("The source returned no entries")
                        null
                    } catch (e: TimeoutCancellationException) {
                        "Health check timed out"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.message?.takeIf(String::isNotBlank)?.take(160) ?: "Health check failed"
                    }
                    if (!enabled()) return@withPermit
                    val result = synchronized(lock) {
                        healthAfterCheck(read(id), System.currentTimeMillis(), error).also {
                            records.edit()
                                .putString("$id.status", it.status.name)
                                .putLong("$id.checked", it.lastChecked)
                                .putLong("$id.success", it.lastSuccess)
                                .putInt("$id.failures", it.failures)
                                .putString("$id.error", it.lastError)
                                .apply()
                        }
                    }
                    onResult(id, result)
                }
            }
        }.awaitAll()
    }

    companion object {
        private val lock = Any()
        private val limiter = Semaphore(3)
        private const val TIMEOUT_MS = 15_000L
        private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L
    }
}
