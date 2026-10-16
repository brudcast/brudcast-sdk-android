package com.brudcast.sdk.inapp

import com.brudcast.sdk.BrudcastLogger
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

internal data class ServerSentEvent(val event: String, val data: String)

/** Splits a server-sent event stream into events, fed one line at a time. */
internal class ServerSentEventParser {
    private var eventName = ""
    private val dataLines = ArrayList<String>()
    var retryMilliseconds: Long? = null
        private set

    /**
     * Feeds one line, without its line break.
     *
     * @param line The line.
     * @return The event the line completed, or null.
     */
    fun feed(line: String): ServerSentEvent? {
        if (line.isEmpty()) {
            if (dataLines.isEmpty() && eventName.isEmpty()) return null
            val event = ServerSentEvent(eventName.ifEmpty { "message" }, dataLines.joinToString("\n"))
            eventName = ""
            dataLines.clear()
            return event
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon >= 0) line.substring(0, colon) else line
        var value = if (colon >= 0) line.substring(colon + 1) else ""
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> eventName = value
            "data" -> dataLines.add(value)
            "retry" -> retryMilliseconds = value.toLongOrNull()
        }
        return null
    }
}

/** Holds the `/stream` connection open while the app is in the foreground. */
internal class StreamClient(
    private val executor: ScheduledExecutorService,
    private val onEvent: (ServerSentEvent) -> Unit,
    private val onUnauthorized: () -> Unit,
) {
    @Volatile
    private var wanted = false

    @Volatile
    private var connection: HttpURLConnection? = null
    private var thread: Thread? = null
    private var failures = 0
    private var reconnect: ScheduledFuture<*>? = null
    private var endpoint: (() -> Pair<String, String>?)? = null
    private var generation = 0

    /** Opens the stream and keeps reopening it after drops until [stop]. Call on the core thread. */
    fun start(endpoint: () -> Pair<String, String>?) {
        this.endpoint = endpoint
        wanted = true
        if (thread == null) connect()
    }

    /** Closes the stream. Call on the core thread. */
    fun stop() {
        wanted = false
        reconnect?.cancel(false)
        generation += 1
        closeConnection()
        thread = null
    }

    /** Reconnects, such as after a session refresh. Call on the core thread. */
    fun restart() {
        if (!wanted) return
        generation += 1
        closeConnection()
        thread = null
        connect()
    }

    private fun closeConnection() {
        val open = connection
        connection = null
        if (open != null) Thread { open.disconnect() }.start()
    }

    private fun connect() {
        if (!wanted) return
        val (url, token) = endpoint?.invoke() ?: return
        val current = generation
        val worker = Thread({ run(url, token, current) }, "brudcast-stream").apply { isDaemon = true }
        thread = worker
        worker.start()
    }

    private fun run(url: String, token: String, current: Int) {
        val parser = ServerSentEventParser()
        var unauthorized = false
        var error: String? = null
        try {
            val open = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "text/event-stream")
                connectTimeout = 15_000
                readTimeout = 90_000
            }
            connection = open
            val status = open.responseCode
            if (status == 401) {
                unauthorized = true
            } else if (status == 200) {
                executor.execute { failures = 0 }
                BufferedReader(InputStreamReader(open.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        parser.feed(line)?.let { event -> executor.execute { if (current == generation) onEvent(event) } }
                    }
                }
            } else {
                error = "HTTP $status"
            }
        } catch (exception: IOException) {
            error = exception.message
        }
        executor.execute {
            if (current != generation) return@execute
            thread = null
            connection = null
            if (unauthorized) {
                onUnauthorized()
                return@execute
            }
            if (!wanted) return@execute
            failures += 1
            val base = (parser.retryMilliseconds ?: 5_000L).toDouble()
            val delay = min(60_000.0, base * 2.0.pow(maxOf(0, failures - 1))).toLong()
            BrudcastLogger.debug("Stream closed${error?.let { ": $it" } ?: ""}; reconnecting in ${delay / 1_000}s")
            reconnect = executor.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
        }
    }
}
