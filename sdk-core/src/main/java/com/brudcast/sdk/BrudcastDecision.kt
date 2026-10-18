package com.brudcast.sdk

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects one answer from a handler that answers later, such as one written in Dart or JavaScript
 * that replies over a bridge. The first of the handler's answer and the timeout's fallback wins, and
 * every later answer is ignored. For modules.
 *
 * @param fallback The answer used when the handler does not answer in time.
 * @param timeoutMs How long to wait for the handler.
 * @param timeoutMessage The warning logged when the fallback is used.
 * @param onDecided Called once with the answer: on the thread that answered, or on the main thread
 *   for the fallback.
 */
public class BrudcastDecision<T : Any>(
    private val fallback: T,
    timeoutMs: Long = HANDLER_TIMEOUT_MS,
    private val timeoutMessage: String,
    private val onDecided: (T) -> Unit,
) {
    private val decided = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        if (decided.compareAndSet(false, true)) {
            BrudcastLogger.warning(timeoutMessage)
            onDecided(fallback)
        }
    }

    init {
        main.postDelayed(timeout, timeoutMs)
    }

    /**
     * Records the handler's answer, unless one was already recorded or the timeout passed.
     *
     * @param value The answer.
     */
    public fun decide(value: T) {
        if (!decided.compareAndSet(false, true)) return
        main.removeCallbacks(timeout)
        onDecided(value)
    }

    /**
     * Records the fallback because the handler failed, unless an answer was already recorded.
     *
     * @param error What the handler threw.
     * @param handlerName The handler's name, for the log.
     */
    public fun fail(error: Throwable, handlerName: String) {
        BrudcastLogger.error("The $handlerName threw: ${error.message}")
        decide(fallback)
    }

    public companion object {
        /** How long the SDK waits for a host handler before it uses the default. */
        public const val HANDLER_TIMEOUT_MS: Long = 2_000L
    }
}
