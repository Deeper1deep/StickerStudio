package com.stickerstudio.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stickerstudio.app.BuildConfig
import com.stickerstudio.app.MainActivity
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Checks for a new release a couple of times a day in the background and posts a notification
 * (once per version) when one is available. It only reads the public update feed; nothing is
 * downloaded or installed without the user opening the app and confirming.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (BuildConfig.UPDATE_REPO_OWNER.isBlank() || !c.settings.autoUpdateCheck.first()) return Result.success()
        val result = withContext(Dispatchers.IO) {
            UpdateChecker(
                HttpsFetcher(), BuildConfig.UPDATE_REPO_OWNER, BuildConfig.UPDATE_REPO_NAME,
                BuildConfig.VERSION_CODE, applicationContext.packageName,
            ).check()
        }
        if (result !is UpdateCheckResult.Available) return Result.success()
        val m = result.manifest
        if (c.settings.skippedVersionCode.first() == m.versionCode) return Result.success()
        if (c.settings.lastNotifiedVersionCode.first() >= m.versionCode) return Result.success()
        if (post(m)) c.settings.setLastNotifiedVersionCode(m.versionCode)
        return Result.success()
    }

    private fun post(m: UpdateManifest): Boolean {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(ctx.getString(R.string.update_notif_title))
            .setContentText(ctx.getString(R.string.update_notif_text, m.versionName))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
        return true
    }

    companion object {
        const val WORK_NAME = "update-check"
        private const val CHANNEL = "updates"
        private const val NOTIF_ID = 4003
    }
}
