package com.stickerstudio.app.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** HTTPS-only GET used for GitHub API / update.json. No token, no cookies. */
class HttpsFetcher : HttpFetcher {
    override fun get(url: String, accept: String?): HttpResponse {
        val u = URL(url)
        if (u.protocol != "https") throw IOException("Only HTTPS is allowed")
        val conn = u.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "StickerStudio-Updater")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (accept != null) conn.setRequestProperty("Accept", accept)

            val code = conn.responseCode
            if (conn.url.protocol != "https") throw IOException("Redirected to a non-HTTPS URL")

            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { readLimited(it, MAX_BODY_BYTES) } ?: ""
            val headers = HashMap<String, String>()
            for ((k, v) in conn.headerFields) {
                if (k != null && v != null && v.isNotEmpty()) headers[k.lowercase()] = v.first()
            }
            return HttpResponse(code, body, headers)
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) throw IOException("Response too large")
        }
        return out.toString("UTF-8")
    }

    private companion object {
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
    }
}

/** Downloads the APK with real progress. Cancelling the coroutine stops and cleans up. */
object ApkDownloader {

    suspend fun download(
        url: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val u = URL(url)
        if (u.protocol != "https") throw IOException("Only HTTPS is allowed")
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, destination.name + ".part")
        part.delete()

        val conn = u.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "StickerStudio-Updater")
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            if (conn.url.protocol != "https") throw IOException("Redirected to a non-HTTPS URL")

            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            destination.delete()
            if (!part.renameTo(destination)) throw IOException("Cannot store the downloaded file")
        } catch (e: Throwable) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
