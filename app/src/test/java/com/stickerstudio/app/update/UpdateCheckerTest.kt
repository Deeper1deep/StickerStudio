package com.stickerstudio.app.update

import com.stickerstudio.app.core.Sha256
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private val appId = "com.stickerstudio.app"
    private val owner = "someone"
    private val repo = "StickerStudio"
    private val sha = "a".repeat(64)
    private val apkUrl = "https://github.com/someone/StickerStudio/releases/download/v1.1.0/StickerStudio-1.1.0.apk"
    private val manifestUrl = "https://github.com/someone/StickerStudio/releases/download/v1.1.0/update.json"
    private val releaseApi = "https://api.github.com/repos/someone/StickerStudio/releases/latest"

    private fun releaseJson(withManifest: Boolean = true): String {
        val apkAsset = """{"name":"StickerStudio-1.1.0.apk","browser_download_url":"$apkUrl","size":1234}"""
        val manifestAsset = """{"name":"update.json","browser_download_url":"$manifestUrl","size":300}"""
        val assets = if (withManifest) "$apkAsset,$manifestAsset" else apkAsset
        return """{"tag_name":"v1.1.0","name":"1.1.0","draft":false,"body":"Release notes body",""" +
            """"html_url":"https://github.com/someone/StickerStudio/releases/tag/v1.1.0",""" +
            """"published_at":"2026-10-20T10:00:00Z","assets":[$assets]}"""
    }

    private fun manifestJson(
        code: Int = 2, name: String = "1.1.0", hash: String = sha, id: String = appId,
        url: String = apkUrl, changelog: String = "- new stuff",
    ) = """{"versionName":"$name","versionCode":$code,"apkUrl":"$url","sha256":"$hash",
            "sizeBytes":1234,"changelog":"$changelog","applicationId":"$id"}"""

    private class Fake(val routes: Map<String, () -> HttpResponse>) : HttpFetcher {
        val requested = mutableListOf<String>()
        override fun get(url: String, accept: String?): HttpResponse {
            requested += url
            return (routes[url] ?: { HttpResponse(404, "") })()
        }
    }

    private fun checker(fetcher: HttpFetcher, installed: Int = 1, o: String = owner, r: String = repo) =
        UpdateChecker(fetcher, o, r, installed, appId)

    private fun ok(body: String) = { HttpResponse(200, body) }

    @Test
    fun notConfiguredWhenOwnerBlank() {
        val f = Fake(emptyMap())
        assertEquals(UpdateCheckResult.NotConfigured, checker(f, o = "").check())
        assertTrue(f.requested.isEmpty())   // no request is made without configuration
    }

    @Test
    fun rejectsUnsafeRepositoryNames() {
        val f = Fake(emptyMap())
        val r = checker(f, o = "a/b").check()
        assertTrue(r is UpdateCheckResult.Failure)
        assertTrue(f.requested.isEmpty())
    }

    @Test
    fun networkFailureIsReported() {
        val f = Fake(mapOf(releaseApi to { throw IOException("no route to host") }))
        val r = checker(f).check() as UpdateCheckResult.Failure
        assertEquals(FailureKind.NETWORK, r.kind)
    }

    @Test
    fun noPublishedReleaseIs404() {
        val f = Fake(mapOf(releaseApi to { HttpResponse(404, "{}") }))
        assertEquals(UpdateCheckResult.NoRelease, checker(f).check())
    }

    @Test
    fun rateLimitIsDetected() {
        val f = Fake(mapOf(releaseApi to { HttpResponse(403, "{}", mapOf("x-ratelimit-remaining" to "0")) }))
        assertEquals(FailureKind.RATE_LIMITED, (checker(f).check() as UpdateCheckResult.Failure).kind)
        val g = Fake(mapOf(releaseApi to { HttpResponse(429, "{}") }))
        assertEquals(FailureKind.RATE_LIMITED, (checker(g).check() as UpdateCheckResult.Failure).kind)
    }

    @Test
    fun forbiddenWithoutRateLimitIsServerError() {
        val f = Fake(mapOf(releaseApi to { HttpResponse(403, "{}", mapOf("x-ratelimit-remaining" to "50")) }))
        assertEquals(FailureKind.SERVER, (checker(f).check() as UpdateCheckResult.Failure).kind)
    }

    @Test
    fun invalidReleaseJsonIsInvalidData() {
        val f = Fake(mapOf(releaseApi to ok("<html>not json</html>")))
        assertEquals(FailureKind.INVALID_DATA, (checker(f).check() as UpdateCheckResult.Failure).kind)
    }

    @Test
    fun releaseWithoutManifestAssetIsReportedAndNeverOffered() {
        val f = Fake(mapOf(releaseApi to ok(releaseJson(withManifest = false))))
        assertEquals(FailureKind.NO_MANIFEST, (checker(f).check() as UpdateCheckResult.Failure).kind)
    }

    @Test
    fun newerVersionCodeIsOffered() {
        val f = Fake(mapOf(releaseApi to ok(releaseJson()), manifestUrl to ok(manifestJson(code = 2))))
        val r = checker(f, installed = 1).check() as UpdateCheckResult.Available
        assertEquals("1.1.0", r.manifest.versionName)
        assertEquals(2, r.manifest.versionCode)
        assertEquals(sha, r.manifest.sha256)
        assertEquals("2026-10-20T10:00:00Z", r.manifest.publishedAt)
        assertEquals("- new stuff", r.manifest.changelog)
    }

    @Test
    fun sameOrOlderVersionCodeIsUpToDate() {
        val f = Fake(mapOf(releaseApi to ok(releaseJson()), manifestUrl to ok(manifestJson(code = 2))))
        assertTrue(checker(f, installed = 2).check() is UpdateCheckResult.UpToDate)
        assertTrue(checker(f, installed = 5).check() is UpdateCheckResult.UpToDate)
    }

    @Test
    fun blankChangelogFallsBackToReleaseNotes() {
        val f = Fake(mapOf(releaseApi to ok(releaseJson()), manifestUrl to ok(manifestJson(changelog = ""))))
        val r = checker(f).check() as UpdateCheckResult.Available
        assertEquals("Release notes body", r.manifest.changelog)
    }

    @Test
    fun manifestForAnotherApplicationIdIsRejected() {
        val f = Fake(mapOf(releaseApi to ok(releaseJson()), manifestUrl to ok(manifestJson(id = "com.evil.app"))))
        assertEquals(FailureKind.INVALID_DATA, (checker(f).check() as UpdateCheckResult.Failure).kind)
    }

    @Test
    fun manifestWithBadHashIsRejected() {
        val f = Fake(mapOf(releaseApi to ok(releaseJson()), manifestUrl to ok(manifestJson(hash = "xyz"))))
        assertEquals(FailureKind.INVALID_DATA, (checker(f).check() as UpdateCheckResult.Failure).kind)
    }

    @Test
    fun apkUrlOutsideTheRepositoryIsRejected() {
        val evil = "https://evil.example.com/app.apk"
        val f = Fake(mapOf(releaseApi to ok(releaseJson()), manifestUrl to ok(manifestJson(url = evil))))
        assertEquals(FailureKind.INVALID_DATA, (checker(f).check() as UpdateCheckResult.Failure).kind)
        val http = "http://github.com/someone/StickerStudio/releases/download/v1/x.apk"
        val g = Fake(mapOf(releaseApi to ok(releaseJson()), manifestUrl to ok(manifestJson(url = http))))
        assertEquals(FailureKind.INVALID_DATA, (checker(g).check() as UpdateCheckResult.Failure).kind)
    }

    @Test
    fun zeroVersionCodeIsRejected() {
        val f = Fake(mapOf(releaseApi to ok(releaseJson()), manifestUrl to ok(manifestJson(code = 0))))
        assertEquals(FailureKind.INVALID_DATA, (checker(f).check() as UpdateCheckResult.Failure).kind)
    }

    @Test
    fun manifestDownloadErrorsAreReported() {
        val f = Fake(mapOf(releaseApi to ok(releaseJson()), manifestUrl to { throw IOException("reset") }))
        assertEquals(FailureKind.NETWORK, (checker(f).check() as UpdateCheckResult.Failure).kind)
        val g = Fake(mapOf(releaseApi to ok(releaseJson()), manifestUrl to { HttpResponse(500, "") }))
        assertEquals(FailureKind.SERVER, (checker(g).check() as UpdateCheckResult.Failure).kind)
    }

    // ---- version helpers -------------------------------------------------------

    @Test
    fun versionNamesAreComparedNumerically() {
        assertEquals(-1, VersionUtils.compareNames("1.9.0", "1.10.0"))
        assertEquals(1, VersionUtils.compareNames("v2.0", "1.99.99"))
        assertEquals(0, VersionUtils.compareNames("1.0", "1.0.0"))
        assertEquals(0, VersionUtils.compareNames("v1.2.3-beta", "1.2.3"))
        assertNull(VersionUtils.compareNames("abc", "1.0"))
    }

    @Test
    fun versionCodeMustIncrease() {
        assertTrue(VersionUtils.isNewerCode(2, 1))
        assertFalse(VersionUtils.isNewerCode(1, 1))
        assertFalse(VersionUtils.isNewerCode(1, 2))
    }

    // ---- hashing ---------------------------------------------------------------

    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".toByteArray()),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(ByteArray(0)),
        )
    }

    @Test
    fun sha256ComparisonIsCaseInsensitiveAndDetectsChanges() {
        val h = Sha256.hex("hello".toByteArray())
        assertTrue(Sha256.matches(h.uppercase(), h))
        assertFalse(Sha256.matches(Sha256.hex("hellp".toByteArray()), h))
        assertTrue(Sha256.isValidHex(h))
        assertFalse(Sha256.isValidHex(h.dropLast(1)))
        assertFalse(Sha256.isValidHex("z".repeat(64)))
    }

    @Test
    fun sha256OfStreamEqualsSha256OfBytes() {
        val data = ByteArray(200_000) { (it * 31).toByte() }
        assertEquals(Sha256.hex(data), Sha256.hex(data.inputStream()))
    }
}
