package com.legs.appsforaa.utils

import android.content.Context
import com.legs.appsforaa.data.ResolvedRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads an APK into app-private cache storage.
 *
 * The file stays inside `cacheDir` deliberately: the installer streams it over stdin rather than
 * handing out a path (see [ShizukuInstaller]), so it never needs to be readable by any other uid,
 * and the OS can reclaim it under storage pressure.
 */
class ApkDownloader(private val context: Context) {

    private companion object {
        const val TAG = "ApkDownloader"
        const val DOWNLOAD_DIR = "apk"
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val READ_TIMEOUT_SECONDS = 120L
        const val BUFFER_BYTES = 64 * 1024
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * @param onProgress fraction in 0..1, or -1 when the server sends no content length.
     *   Called from the IO dispatcher; marshal to the main thread yourself.
     * @throws IllegalStateException on a non-2xx response or an empty body.
     */
    suspend fun download(
        release: ResolvedRelease,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = File(downloadDir(), sanitize(release.assetName))
        // A partial file from an interrupted run must never be installed.
        if (target.exists()) target.delete()

        val request = Request.Builder().url(release.downloadUrl).build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
            val body = response.body ?: error("Download failed: empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes

            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var written = 0L
                    while (true) {
                        // Cancelling the coroutine must stop the transfer, not just orphan it.
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(if (total > 0) written.toFloat() / total else -1f)
                    }
                }
            }
        }

        Logger.d(TAG, "Downloaded ${target.name} (${target.length()} bytes)")
        target
    }

    /** Clears previously downloaded APKs; they are reproducible and only cost space. */
    fun clearCache() {
        runCatching { downloadDir().listFiles()?.forEach { it.delete() } }
            .onFailure { Logger.w(TAG, "Failed to clear APK cache", it) }
    }

    private fun downloadDir(): File =
        File(context.cacheDir, DOWNLOAD_DIR).apply { mkdirs() }

    /** Asset names come from a remote server; never let one escape the download directory. */
    private fun sanitize(name: String): String =
        name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "download.apk" }
}
