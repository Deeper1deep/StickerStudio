package com.stickerstudio.app

import android.app.Application
import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.stickerstudio.app.data.AppDatabase
import com.stickerstudio.app.data.PackRepository
import com.stickerstudio.app.data.SettingsStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.stickerstudio.app.update.UpdateCheckWorker
import com.stickerstudio.app.update.UpdateManager
import java.util.concurrent.TimeUnit

/** Manual dependency container (the app is small enough that a DI framework is not needed). */
class AppContainer(app: Application) {
    val database: AppDatabase by lazy { AppDatabase.get(app) }
    val repository: PackRepository by lazy { PackRepository(app, database) }
    val settings: SettingsStore = SettingsStore(app)
    val updates: UpdateManager = UpdateManager(app, settings)
}

class StickerStudioApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        scheduleUpdateChecks()
    }

    /** Background check for a new release roughly twice a day, only with a network connection. */
    private fun scheduleUpdateChecks() {
        if (BuildConfig.UPDATE_REPO_OWNER.isBlank()) return
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(UpdateCheckWorker.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Coil loader that can also play animated WebP / GIF stickers. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .build()
}

val Context.container: AppContainer
    get() = (applicationContext as StickerStudioApp).container
