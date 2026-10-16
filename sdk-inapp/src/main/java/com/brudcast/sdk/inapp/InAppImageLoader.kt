package com.brudcast.sdk.inapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.brudcast.sdk.BrudcastLogger
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Downloads and caches message images with the platform's URL loading. */
internal object InAppImageLoader {
    private val cache = object : LruCache<String, Bitmap>(16 * 1_024 * 1_024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val io = Executors.newFixedThreadPool(3) { Thread(it, "brudcast-images").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "brudcast-images-timer").apply { isDaemon = true } }

    fun image(url: String): Bitmap? = cache.get(url)

    /**
     * Loads every image before a message shows.
     *
     * @param urls The design's image URLs.
     * @param timeoutMs How long to wait for all of them.
     * @param completion Called once with true when every image loaded in time.
     */
    fun prefetch(urls: List<String>, timeoutMs: Long, completion: (Boolean) -> Unit) {
        val missing = urls.distinct().filter { cache.get(it) == null }
        if (missing.isEmpty()) {
            completion(true)
            return
        }
        val finished = AtomicBoolean(false)
        val remaining = AtomicInteger(missing.size)
        val finish: (Boolean) -> Unit = { ok -> if (finished.compareAndSet(false, true)) completion(ok) }
        missing.forEach { url ->
            load(url, timeoutMs.toInt()) { bitmap ->
                if (bitmap == null) finish(false) else if (remaining.decrementAndGet() == 0) finish(true)
            }
        }
        timer.schedule({ finish(false) }, timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Loads one image.
     *
     * @param url The image URL.
     * @param timeoutMs The connection and read timeout.
     * @param completion Called on a background thread with the bitmap, or null when it failed.
     */
    fun load(url: String, timeoutMs: Int = 15_000, completion: (Bitmap?) -> Unit) {
        cache.get(url)?.let {
            completion(it)
            return
        }
        io.execute {
            var connection: HttpURLConnection? = null
            val bitmap = try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                }
                if (connection.responseCode in 200..299) connection.inputStream.use(BitmapFactory::decodeStream) else null
            } catch (error: IOException) {
                null
            } finally {
                connection?.disconnect()
            }
            if (bitmap == null) {
                BrudcastLogger.debug("Image failed to load: $url")
            } else {
                cache.put(url, bitmap)
            }
            completion(bitmap)
        }
    }
}
