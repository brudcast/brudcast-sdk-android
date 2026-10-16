package com.brudcast.sdk

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * The SDK's logger. Modules log through it so one level controls everything.
 */
public object BrudcastLogger {
    private const val TAG = "Brudcast"

    @Volatile
    internal var level: BrudcastLogLevel = BrudcastLogLevel.WARNING

    @Volatile
    internal var sink: ((BrudcastLogLevel, String) -> Unit)? = null

    private val once = ConcurrentHashMap.newKeySet<String>()

    /**
     * Logs a message at the given level.
     *
     * @param level The message's level.
     * @param message Builds the message, only when the level is enabled.
     */
    public fun log(level: BrudcastLogLevel, message: () -> String) {
        if (level == BrudcastLogLevel.NONE || level < this.level) return
        val text = message()
        sink?.let {
            it(level, text)
            return
        }
        try {
            when (level) {
                BrudcastLogLevel.VERBOSE -> Log.v(TAG, text)
                BrudcastLogLevel.DEBUG -> Log.d(TAG, text)
                BrudcastLogLevel.INFO -> Log.i(TAG, text)
                BrudcastLogLevel.WARNING -> Log.w(TAG, text)
                BrudcastLogLevel.ERROR, BrudcastLogLevel.NONE -> Log.e(TAG, text)
            }
        } catch (_: RuntimeException) {
            println("[$TAG] $text")
        }
    }

    /**
     * Logs a message the first time a key is seen, and never again in this process.
     *
     * @param key What makes the message unique.
     * @param level The message's level.
     * @param message The message.
     */
    public fun logOnce(key: String, level: BrudcastLogLevel, message: () -> String) {
        if (once.add(key)) log(level, message)
    }

    public fun debug(message: String): Unit = log(BrudcastLogLevel.DEBUG) { message }

    public fun info(message: String): Unit = log(BrudcastLogLevel.INFO) { message }

    public fun warning(message: String): Unit = log(BrudcastLogLevel.WARNING) { message }

    public fun error(message: String): Unit = log(BrudcastLogLevel.ERROR) { message }
}
