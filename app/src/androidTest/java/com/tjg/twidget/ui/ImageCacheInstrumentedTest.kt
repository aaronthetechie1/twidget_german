package com.tjg.twidget.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageCacheInstrumentedTest {
    @Test fun collisionsDoNotMixAvatarsAndRepeatedDecodesShareSampledBitmap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "https://example.com/audit-${System.nanoTime()}/"
        val urls = listOf(prefix + "Aa", prefix + "BB")
        assertEquals(urls[0].hashCode(), urls[1].hashCode())
        val files = urls.mapIndexed { index, url ->
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            File(context.cacheDir, "profile_avatar_$digest.png").also { file ->
                val bitmap = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(if (index == 0) Color.RED else Color.BLUE)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Bitmap?> { ProfileImageLoader.cachedBitmap(context, urls[0]) }
            val b = pool.submit<Bitmap?> { ProfileImageLoader.cachedBitmap(context, urls[0]) }
            val first = a.get(5, TimeUnit.SECONDS)!!
            assertSame(first, b.get(5, TimeUnit.SECONDS))
            assertTrue(first.width <= 400 && first.height <= 400)
            assertEquals(Color.RED, first.getPixel(0, 0))
            assertEquals(Color.BLUE, ProfileImageLoader.cachedBitmap(context, urls[1])!!.getPixel(0, 0))
            files[0].delete()
            assertSame("Decoded offline hit needs no file", first, ProfileImageLoader.cachedBitmap(context, urls[0]))
        } finally { pool.shutdownNow(); files.forEach { it.delete() } }
    }
}
