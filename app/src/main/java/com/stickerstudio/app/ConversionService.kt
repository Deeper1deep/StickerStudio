package com.stickerstudio.app

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the process alive (and shows a progress notification) while a sticker is being converted,
 * so leaving the app does not freeze or kill a long conversion. The work itself still runs in the
 * view model; this service only holds the process in the foreground and mirrors the progress.
 */
class ConversionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val first = build(this, progress.value ?: 0f)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, first)
        if (job == null) {
            job = scope.launch {
                progress.collect { p ->
                    if (p == null) {
                        finish()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(ID, build(this@ConversionService, p))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        val ok = lastSucceeded
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (ok && !appVisible(this)) {
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.convert_done_title))
                .setContentText(getString(R.string.convert_done_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(DONE_ID, n)
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "conversion"
        private const val ID = 4001
        private const val DONE_ID = 4002

        /** 0..1 while converting, null when nothing is running. */
        val progress = MutableStateFlow<Float?>(null)
        @Volatile private var lastSucceeded = false

        fun begin(context: Context) {
            lastSucceeded = false
            progress.value = 0f
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConversionService::class.java))
            } catch (e: Exception) {
                // the conversion still works, it just is not protected while the app is in the background
            }
        }

        fun update(p: Float) { progress.value = p.coerceIn(0f, 1f) }

        fun end(success: Boolean) {
            lastSucceeded = success
            progress.value = null
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.convert_channel), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        private fun build(context: Context, p: Float): Notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.convert_working))
                .setContentText("${(p * 100).toInt()}%")
                .setProgress(100, (p * 100).toInt(), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                .build()

        private fun appVisible(context: Context): Boolean {
            val am = context.getSystemService(ActivityManager::class.java)
            val me = am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() } ?: return false
            return me.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }
}
