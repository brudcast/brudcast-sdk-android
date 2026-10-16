package com.brudcast.sdk.inapp

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.brudcast.sdk.BrudcastLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads and caches message images with the platform's URL loading.
 *
 * Images are scaled down to the screen when decoded, so each one fits the memory cache, and the
 * downloaded bytes are kept on disk so a message seen again needs no network.
 */
internal object InAppImageLoader {
    private const val MAX_DOWNLOAD_BYTES = 10 * 1_024 * 1_024
    private const val MAX_DISK_BYTES = 50L * 1_024 * 1_024
    private const val MAX_MEMORY_BYTES = 32 * 1_024 * 1_024

    private val memoryBytes = minOf(MAX_MEMORY_BYTES.toLong(), Runtime.getRuntime().maxMemory() / 8).toInt()
    private val cache = object : LruCache<String, Bitmap>(memoryBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val io = Executors.newFixedThreadPool(3) { Thread(it, "brudcast-images").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "brudcast-images-timer").apply { isDaemon = true } }

    @Volatile
    private var diskDirectory: File? = null

    /**
     * Gives the loader a directory for its disk cache.
     *
     * @param cacheDir The app's cache directory.
     */
    fun install(cacheDir: File) {
        diskDirectory = File(cacheDir, "brudcast-images").apply { mkdirs() }
    }

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
     * Loads one image, from memory, then disk, then the network.
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
            val bitmap = try {
                (readDisk(url) ?: download(url, timeoutMs)?.also { writeDisk(url, it) })?.let(::decode)
            } catch (error: IOException) {
                null
            } catch (error: RuntimeException) {
                null
            }
            if (bitmap == null) {
                BrudcastLogger.debug("Image failed to load: $url")
            } else {
                cache.put(url, bitmap)
            }
            completion(bitmap)
        }
    }

    private fun download(url: String, timeoutMs: Int): ByteArray? {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            if (connection.responseCode !in 200..299 || connection.contentLength > MAX_DOWNLOAD_BYTES) return null
            return connection.inputStream.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1_024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > MAX_DOWNLOAD_BYTES) return null
                }
                buffer.toByteArray()
            }
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Decodes image bytes no larger than the screen's longest edge, halving further until the
     * bitmap fits the memory cache, so a loaded image is always there when the message draws.
     *
     * @param bytes The encoded image.
     * @return The bitmap, or null when the bytes are not an image.
     */
    internal fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val metrics = Resources.getSystem().displayMetrics
        val maxEdge = maxOf(metrics.widthPixels, metrics.heightPixels, 1_080)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) * 4 > memoryBytes / 2) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun diskFile(url: String): File? {
        val directory = diskDirectory ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { "%02x".format(it) })
    }

    private fun readDisk(url: String): ByteArray? {
        val file = diskFile(url)?.takeIf { it.exists() } ?: return null
        file.setLastModified(System.currentTimeMillis())
        return file.readBytes()
    }

    private fun writeDisk(url: String, bytes: ByteArray) {
        val file = diskFile(url) ?: return
        try {
            file.writeBytes(bytes)
            trimDisk(file.parentFile ?: return)
        } catch (error: IOException) {
            BrudcastLogger.debug("Could not cache image $url: ${error.message}")
        }
    }

    private fun trimDisk(directory: File) {
        val files = directory.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (file in files) {
            total += file.length()
            if (total > MAX_DISK_BYTES) file.delete()
        }
    }
}
