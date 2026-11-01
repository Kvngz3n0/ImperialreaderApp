package com.imperialreader.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.imperialreader.util.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class AppUpdateManager(context: Context) {

    private val ctx = context.applicationContext

    suspend fun downloadLatestApk(): File = withContext(Dispatchers.IO) {
        val asset = latestApkAsset()
        val assetUrl = asset.optString("browser_download_url")
        if (!assetUrl.startsWith("https://github.com/")) {
            throw IOException("The latest APK has an invalid download address")
        }

        val directory = File(ctx.cacheDir, UPDATE_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create the APK download folder")
        }
        val temporary = File(directory, "$APK_FILE.part")
        val apk = File(directory, APK_FILE)
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("Could not prepare the APK download")
        }

        try {
            val request = Request.Builder().url(assetUrl).build()
            ctx.app.network.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("APK download failed (HTTP ${response.code})")
                val body = response.body ?: throw IOException("GitHub returned an empty APK")
                if (body.contentLength() > MAX_APK_SIZE_BYTES) {
                    throw IOException("The APK download is too large")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_APK_SIZE_BYTES) throw IOException("The APK download is too large")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val expectedDigest = asset.optString("digest")
                    .takeIf { it.startsWith("sha256:", ignoreCase = true) }
                    ?.substringAfter(':')
                if (expectedDigest != null && !digest.digest().toHex().equals(expectedDigest, true)) {
                    throw IOException("The downloaded APK failed its SHA-256 check")
                }
            }

            val flags = packageInfoFlags()
            val archiveInfo = ctx.packageManager.getPackageArchiveInfo(temporary.absolutePath, flags)
                ?: throw IOException("The downloaded file is not a valid APK")
            if (archiveInfo.packageName != ctx.packageName) {
                throw IOException("The downloaded APK is for a different app")
            }
            val installedInfo = ctx.packageManager.getPackageInfo(ctx.packageName, flags)
            if (versionCode(archiveInfo) <= versionCode(installedInfo)) {
                throw IOException("The V9 release does not contain a newer app version")
            }
            if (signingCertificates(archiveInfo) != signingCertificates(installedInfo)) {
                throw IOException(
                    "This APK is signed with a different key and cannot update the installed app. " +
                        "A matching release signing key is required."
                )
            }
            if (apk.exists() && !apk.delete()) throw IOException("Could not replace the previous APK download")
            if (!temporary.renameTo(apk)) throw IOException("Could not finish saving the APK")
            apk
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
    }

    private fun latestApkAsset(): JSONObject {
        val request = Request.Builder()
            .url(RELEASE_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ImperialReader")
            .build()
        val release = ctx.app.network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub release lookup failed (HTTP ${response.code})")
            JSONObject(response.body?.string() ?: throw IOException("GitHub returned an empty release response"))
        }
        val assets = release.optJSONArray("assets") ?: throw IOException("The V9 release has no assets")
        return (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            .maxByOrNull { it.optString("created_at") }
            ?: throw IOException("No APK is available in the V9 release")
    }

    private fun packageInfoFlags(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return PackageManager.GET_SIGNING_CERTIFICATES
        }
        @Suppress("DEPRECATION")
        return PackageManager.GET_SIGNATURES
    }

    private fun signingCertificates(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: throw IOException("Could not verify the APK signing certificate")
        return signers.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }.toSet()
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val RELEASE_API_URL =
            "https://api.github.com/repos/Kvngz3n0/ImperialreaderApp/releases/tags/V9"
        const val UPDATE_DIRECTORY = "app-updates"
        const val APK_FILE = "latest.apk"
        const val MAX_APK_SIZE_BYTES = 200L * 1024L * 1024L
    }
}
