package com.stickerstudio.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.stickerstudio.app.core.Sha256
import java.io.File

enum class VerifyResult {
    OK,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    NOT_AN_APK,
    WRONG_PACKAGE,
    NOT_NEWER,
    SIGNATURE_MISMATCH,
}

/**
 * Every check that must pass before the system installer is opened:
 * size, SHA-256 of the manifest, that it is an APK of THIS app, that its versionCode is
 * higher, and that it is signed with the same certificate as the installed app (otherwise
 * Android would refuse to install it over the existing one anyway).
 */
object ApkVerifier {

    fun verify(context: Context, file: File, manifest: UpdateManifest): VerifyResult {
        if (!file.isFile) return VerifyResult.NOT_AN_APK
        if (manifest.sizeBytes > 0 && file.length() != manifest.sizeBytes) return VerifyResult.SIZE_MISMATCH
        if (!Sha256.matches(Sha256.hex(file), manifest.sha256)) return VerifyResult.HASH_MISMATCH

        val pm = context.packageManager
        val archive = archiveInfo(pm, file) ?: return VerifyResult.NOT_AN_APK
        if (archive.packageName != context.packageName) return VerifyResult.WRONG_PACKAGE

        val archiveCode = versionCode(archive)
        val installedCode = versionCode(installedInfo(pm, context.packageName) ?: return VerifyResult.NOT_AN_APK)
        if (archiveCode <= installedCode) return VerifyResult.NOT_NEWER

        val installedCerts = certDigests(installedInfo(pm, context.packageName))
        val archiveCerts = certDigests(archive)
        if (installedCerts.isEmpty() || archiveCerts.isEmpty() || installedCerts.intersect(archiveCerts).isEmpty()) {
            return VerifyResult.SIGNATURE_MISMATCH
        }
        return VerifyResult.OK
    }

    @Suppress("DEPRECATION")
    private fun signatureFlag(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? =
        try {
            pm.getPackageArchiveInfo(file.path, signatureFlag())
        } catch (e: Exception) {
            null
        }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, packageName: String): PackageInfo? =
        try {
            pm.getPackageInfo(packageName, signatureFlag())
        } catch (e: Exception) {
            null
        }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun certDigests(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val signatures: Array<android.content.pm.Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo
            when {
                si == null -> null
                si.hasMultipleSigners() -> si.apkContentsSigners
                else -> si.signingCertificateHistory
            }
        } else {
            info.signatures
        }
        if (signatures == null) return emptySet()
        return signatures.map { sig -> Sha256.hex(sig.toByteArray()) }.toSet()
    }
}
