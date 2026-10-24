package com.stickerstudio.app.update

import com.stickerstudio.app.core.JsonParseException
import com.stickerstudio.app.core.MiniJson
import com.stickerstudio.app.core.Sha256
import com.stickerstudio.app.core.asArray
import com.stickerstudio.app.core.asObject
import com.stickerstudio.app.core.optInt
import com.stickerstudio.app.core.optLong
import com.stickerstudio.app.core.optString
import java.io.IOException

/** Release metadata published next to every APK (asset "update.json"). */
data class UpdateManifest(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val changelog: String,
    val applicationId: String,
    val publishedAt: String?,
    val releasePageUrl: String?,
)

data class HttpResponse(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

/** Blocking HTTP GET. Implementations must throw [IOException] on connectivity problems. */
fun interface HttpFetcher {
    @Throws(IOException::class)
    fun get(url: String, accept: String?): HttpResponse
}

enum class FailureKind { NETWORK, RATE_LIMITED, SERVER, INVALID_DATA, NO_MANIFEST }

sealed interface UpdateCheckResult {
    /** The repository owner/name were never configured at build time. */
    data object NotConfigured : UpdateCheckResult

    /** The repository exists but has no published release yet. */
    data object NoRelease : UpdateCheckResult

    data class UpToDate(val latest: UpdateManifest) : UpdateCheckResult
    data class Available(val manifest: UpdateManifest) : UpdateCheckResult
    data class Failure(val kind: FailureKind, val detail: String? = null) : UpdateCheckResult
}

/**
 * Looks at the latest GitHub Release of the configured repository and reads its
 * `update.json` asset. An update is only reported when real, valid release data was read.
 */
class UpdateChecker(
    private val fetcher: HttpFetcher,
    private val owner: String,
    private val repo: String,
    private val installedVersionCode: Int,
    private val expectedApplicationId: String,
) {

    fun check(): UpdateCheckResult {
        if (owner.isBlank() || repo.isBlank()) return UpdateCheckResult.NotConfigured
        if (!isSafeSegment(owner) || !isSafeSegment(repo)) {
            return UpdateCheckResult.Failure(FailureKind.INVALID_DATA, "bad repository name")
        }

        val releaseUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val releaseResp = try {
            fetcher.get(releaseUrl, "application/vnd.github+json")
        } catch (e: IOException) {
            return UpdateCheckResult.Failure(FailureKind.NETWORK, e.message)
        }

        when {
            releaseResp.code == 404 -> return UpdateCheckResult.NoRelease
            releaseResp.code == 403 || releaseResp.code == 429 -> {
                val remaining = releaseResp.headers["x-ratelimit-remaining"]
                return if (releaseResp.code == 429 || remaining == "0") {
                    UpdateCheckResult.Failure(FailureKind.RATE_LIMITED)
                } else {
                    UpdateCheckResult.Failure(FailureKind.SERVER, "HTTP ${releaseResp.code}")
                }
            }
            releaseResp.code != 200 -> return UpdateCheckResult.Failure(FailureKind.SERVER, "HTTP ${releaseResp.code}")
        }

        val release = try {
            MiniJson.parse(releaseResp.body).asObject()
        } catch (e: JsonParseException) {
            null
        } ?: return UpdateCheckResult.Failure(FailureKind.INVALID_DATA, "release json")

        if (release["draft"] == true) return UpdateCheckResult.NoRelease

        val releaseBody = release.optString("body") ?: ""
        val releasePage = release.optString("html_url")
        val publishedAt = release.optString("published_at")

        val manifestUrl = release["assets"].asArray().orEmpty()
            .mapNotNull { it.asObject() }
            .firstOrNull { it.optString("name") == MANIFEST_ASSET_NAME }
            ?.optString("browser_download_url")
            ?: return UpdateCheckResult.Failure(FailureKind.NO_MANIFEST)

        if (!manifestUrl.startsWith("https://")) {
            return UpdateCheckResult.Failure(FailureKind.INVALID_DATA, "manifest url is not https")
        }

        val manifestResp = try {
            fetcher.get(manifestUrl, "application/json")
        } catch (e: IOException) {
            return UpdateCheckResult.Failure(FailureKind.NETWORK, e.message)
        }
        if (manifestResp.code != 200) {
            return UpdateCheckResult.Failure(FailureKind.SERVER, "HTTP ${manifestResp.code}")
        }

        val m = try {
            MiniJson.parse(manifestResp.body).asObject()
        } catch (e: JsonParseException) {
            null
        } ?: return UpdateCheckResult.Failure(FailureKind.INVALID_DATA, "update.json")

        val manifest = UpdateManifest(
            versionName = m.optString("versionName") ?: "",
            versionCode = m.optInt("versionCode", 0),
            apkUrl = m.optString("apkUrl") ?: "",
            sha256 = m.optString("sha256") ?: "",
            sizeBytes = m.optLong("sizeBytes", 0L),
            changelog = (m.optString("changelog") ?: "").ifBlank { releaseBody },
            applicationId = m.optString("applicationId") ?: "",
            publishedAt = m.optString("publishedAt") ?: publishedAt,
            releasePageUrl = releasePage,
        )

        val problem = validate(manifest)
        if (problem != null) return UpdateCheckResult.Failure(FailureKind.INVALID_DATA, problem)

        return if (VersionUtils.isNewerCode(manifest.versionCode, installedVersionCode)) {
            UpdateCheckResult.Available(manifest)
        } else {
            UpdateCheckResult.UpToDate(manifest)
        }
    }

    /** Returns a description of the first problem, or null if the manifest is acceptable. */
    private fun validate(m: UpdateManifest): String? {
        if (m.versionName.isBlank()) return "versionName missing"
        if (m.versionCode <= 0) return "versionCode invalid"
        if (!Sha256.isValidHex(m.sha256)) return "sha256 invalid"
        if (m.sizeBytes < 0) return "size invalid"
        if (m.applicationId != expectedApplicationId) return "applicationId mismatch"
        val allowedPrefix = "https://github.com/$owner/$repo/releases/download/".lowercase()
        if (!m.apkUrl.lowercase().startsWith(allowedPrefix)) return "apkUrl is not a release asset of this repository"
        return null
    }

    private fun isSafeSegment(s: String): Boolean =
        s.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } && s != "." && s != ".."

    companion object {
        const val MANIFEST_ASSET_NAME = "update.json"
    }
}
