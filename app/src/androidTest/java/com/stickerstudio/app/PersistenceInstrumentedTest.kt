package com.stickerstudio.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stickerstudio.app.data.AppDatabase
import com.stickerstudio.app.data.PackRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Instrumented tests. NOT RUN in the authoring environment (no Android SDK/emulator). */
@RunWith(AndroidJUnit4::class)
class PersistenceInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun packsAndStickersSurviveReopeningTheDatabase() = runBlocking {
        val name = "test-${System.nanoTime()}.db"
        val first = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val repo = PackRepository(context, first)
        val id = repo.createPack("Pack", "Me")
        first.close()

        val second = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val reopened = PackRepository(context, second).findPack(id)
        assertEquals("Pack", reopened?.name)
        second.close()
        context.deleteDatabase(name)
    }

    @Test
    fun webpBytesStartWithRiffHeader() {
        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
        val bytes = out.toByteArray()
        assertTrue(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP")
    }
}
