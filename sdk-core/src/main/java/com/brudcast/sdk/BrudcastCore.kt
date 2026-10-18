package com.brudcast.sdk

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

internal data class BrudcastIdentity(
    val externalId: String,
    val userHash: String?,
    val traits: BrudcastTraits?,
)

/**
 * The SDK's state machine. Everything mutable is touched only on [executor].
 */
internal class BrudcastCore(
    private val application: Application,
    publishableKey: String,
    val options: BrudcastOptions,
    private val storage: BrudcastStorage,
) {
    val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "brudcast-core").apply { isDaemon = true }
    }
    private val io = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "brudcast-io").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    val api = BrudcastApiClient(options.environment.baseUrl, publishableKey, io)
    val deviceId: String = storage.deviceId()
    val modules: List<BrudcastModule> = options.modules
    val activities = ActivityTracker()
    val context = BrudcastContext(this, application, options, storage, api, executor, deviceId)

    @Volatile
    var session: BrudcastSession? = null
        private set

    @Volatile
    var isAppActive: Boolean = false
        private set

    @Volatile
    var actionHandler: BrudcastAsyncActionHandler? = null

    private val events = EventQueue(storage)
    private var identity: BrudcastIdentity? = loadIdentity()
    private var eventOwner: String? = identity?.externalId
    private val retiredSessions = HashMap<String, BrudcastSession>()
    private var persistScheduled = false
    private var identityRejected = false
    private var keyRejected = false
    private var trackingDisabled = storage.bool(TRACKING_DISABLED_KEY)
    private var opening = false
    private val openWaiters = ArrayList<(BrudcastSession?, BrudcastApiError?) -> Unit>()
    private var sessionFailures = 0
    private var sessionRetry: ScheduledFuture<*>? = null
    private var refreshTimer: ScheduledFuture<*>? = null
    private var flushTimer: ScheduledFuture<*>? = null
    private var flushing = false
    private val flushWaiters = ArrayList<() -> Unit>()

    fun start() {
        application.registerActivityLifecycleCallbacks(activities)
        activities.onResumed = { activity -> post { modules.forEach { it.activityResumed(activity) } } }
        post {
            modules.forEach { it.attach(context) }
            openSession { _, _ -> }
        }
        main.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    post { appDidBecomeActive() }
                }

                override fun onStop(owner: LifecycleOwner) {
                    post { appDidEnterBackground() }
                }
            })
        }
    }

    fun post(task: () -> Unit) {
        executor.execute { guarded(task) }
    }

    private fun schedule(delayMs: Long, task: () -> Unit): ScheduledFuture<*> =
        executor.schedule({ guarded(task) }, delayMs, TimeUnit.MILLISECONDS)

    /**
     * Runs a task on the executor, logging any exception instead of letting it kill the thread and
     * crash the app. `org.json` throws the checked `JSONException`, so every exception is caught.
     *
     * @param task The task.
     */
    private fun guarded(task: () -> Unit) {
        try {
            task()
        } catch (error: Exception) {
            BrudcastLogger.error("Internal error: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    fun identify(externalId: String, userHash: String?, traits: BrudcastTraits?) {
        val trimmed = externalId.trim()
        if (trimmed.isEmpty() || trimmed.length > 191) {
            BrudcastLogger.error("identify needs an external id of 1 to 191 characters")
            return
        }
        if (userHash != null && !HASH_PATTERN.matches(userHash)) {
            BrudcastLogger.error("identify was given a user hash that is not a hex HMAC-SHA256; compute it on your server")
            return
        }
        post {
            val next = BrudcastIdentity(trimmed, userHash, traits)
            if (next == identity && session?.externalId == trimmed && !identityRejected) return@post
            val userChanged = identity?.externalId != trimmed
            eventOwner = trimmed
            flushThen {
                identity = next
                identityRejected = false
                persistIdentity()
                if (userChanged) {
                    retire(session)
                    session = null
                }
                reopenSession()
            }
        }
    }

    fun reset() {
        post {
            val previous = session
            eventOwner = null
            flushThen {
                val finish = {
                    identity = null
                    identityRejected = false
                    persistIdentity()
                    retire(session ?: previous)
                    session = null
                    modules.forEach { it.didReset() }
                    reopenSession()
                }
                if (previous == null) {
                    finish()
                    return@flushThen
                }
                var remaining = modules.size
                if (remaining == 0) {
                    finish()
                    return@flushThen
                }
                modules.forEach { module ->
                    module.sessionWillReset(previous) {
                        post {
                            remaining -= 1
                            if (remaining == 0) finish()
                        }
                    }
                }
            }
        }
    }

    fun track(name: String, properties: Map<String, BrudcastValue>) {
        if (!isValidEventName(name)) {
            BrudcastLogger.error("track ignored \"$name\": names start with a letter and use at most 64 letters, digits and _ . : -")
            return
        }
        post {
            enqueue(BrudcastEvent(type = "custom", name = name, properties = properties))
            modules.forEach { it.didTrack(name, properties) }
        }
    }

    fun screen(name: String, properties: Map<String, BrudcastValue>) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 64) {
            BrudcastLogger.error("screen needs a name of 1 to 64 characters")
            return
        }
        post {
            enqueue(BrudcastEvent(type = "screen", name = trimmed, properties = properties))
            modules.forEach { it.didScreen(trimmed, properties) }
        }
    }

    fun setTrackingEnabled(enabled: Boolean) {
        post {
            trackingDisabled = !enabled
            storage.setBool(TRACKING_DISABLED_KEY, !enabled)
            if (!enabled) events.clear()
        }
    }

    fun setOptedOut(optedOut: Boolean) {
        post {
            authorizedRequest(BrudcastHttpMethod.POST, if (optedOut) "/opt-out" else "/opt-in", emptyMap(), null, false) { result ->
                if (result is BrudcastResult.Failure) {
                    BrudcastLogger.warning("Could not change the opt-out choice: ${result.error}")
                }
            }
        }
    }

    private fun reopenSession() {
        sessionRetry?.cancel(false)
        sessionFailures = 0
        openSession { _, _ -> }
    }

    fun openSession(completion: (BrudcastSession?, BrudcastApiError?) -> Unit) {
        openWaiters.add(completion)
        if (opening) return
        if (keyRejected) {
            resolveWaiters(null, BrudcastApiError(401, "key_rejected", "The publishable key was refused"))
            return
        }
        if (identityRejected) {
            resolveWaiters(null, BrudcastApiError(401, "identity_rejected", "The user hash was refused"))
            return
        }
        opening = true
        sessionRetry?.cancel(false)

        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("platform", BrudcastSdk.PLATFORM)
            .put("sdkVersion", BrudcastSdk.VERSION)
            .put("modules", JSONArray(modules.map { it.name }))
        appVersion()?.let { body.put("appVersion", it) }
        val requested = identity
        requested?.let {
            body.put("externalId", it.externalId)
            it.userHash?.let { hash -> body.put("userHash", hash) }
            val traits = it.traits?.toJson()
            if (traits != null && traits.length() > 0) body.put("traits", traits)
        }

        api.send(BrudcastHttpMethod.POST, "/sessions", body = body) { result ->
            post {
                opening = false
                when (result) {
                    is BrudcastResult.Success -> {
                        val opened = parseSession(result.data)
                        if (opened == null) {
                            BrudcastLogger.error("The session response could not be read")
                            scheduleSessionRetry()
                            resolveWaiters(null, BrudcastApiError(null, "decoding", "Unreadable session"))
                            return@post
                        }
                        if (requested != identity) {
                            openSession { _, _ -> }
                            return@post
                        }
                        sessionFailures = 0
                        session = opened
                        scheduleRefresh(opened)
                        BrudcastLogger.info("Session open for ${opened.externalId ?: "an anonymous user"}${if (opened.verified) " (verified)" else ""}")
                        modules.forEach { it.sessionDidOpen(opened, result.data) }
                        resolveWaiters(opened, null)
                        flush()
                    }
                    is BrudcastResult.Failure -> {
                        handleSessionFailure(result.error, requested != null)
                        resolveWaiters(null, result.error)
                    }
                }
            }
        }
    }

    private fun handleSessionFailure(error: BrudcastApiError, hadIdentity: Boolean) {
        if (error.statusCode == 401) {
            if (!isKeyRejection(error, hadIdentity)) {
                identityRejected = true
                BrudcastLogger.error("The service refused this user's identity: ${error.message}. Compute userHash on your server as HMAC-SHA256(identitySecret, externalId), and call identify again.")
            } else {
                keyRejected = true
                BrudcastLogger.error("The service refused the publishable key: ${error.message}")
            }
            return
        }
        if (error.isRetryable) {
            BrudcastLogger.info("Could not open a session, retrying: $error")
            scheduleSessionRetry()
        } else {
            BrudcastLogger.error("Could not open a session: $error")
        }
    }

    private fun resolveWaiters(opened: BrudcastSession?, error: BrudcastApiError?) {
        val waiters = openWaiters.toList()
        openWaiters.clear()
        waiters.forEach { it(opened, error) }
    }

    private fun scheduleSessionRetry() {
        sessionFailures += 1
        val delay = min(MAX_SESSION_BACKOFF_MS.toDouble(), 2_000 * 2.0.pow(sessionFailures - 1)).toLong()
        sessionRetry = schedule(delay) {
            if (session == null || isExpiring(session)) openSession { _, _ -> }
        }
    }

    private fun scheduleRefresh(opened: BrudcastSession) {
        refreshTimer?.cancel(false)
        val delay = maxOf(5_000L, opened.expiresAt - System.currentTimeMillis() - REFRESH_LEEWAY_MS)
        refreshTimer = schedule(delay) {
            if (session == opened) openSession { _, _ -> }
        }
    }

    private fun isExpiring(current: BrudcastSession?): Boolean {
        current ?: return true
        return current.expiresAt - System.currentTimeMillis() < REFRESH_LEEWAY_MS / 2
    }

    private fun withSession(completion: (BrudcastSession?, BrudcastApiError?) -> Unit) {
        val current = session
        if (current != null && !isExpiring(current)) {
            completion(current, null)
            return
        }
        openSession(completion)
    }

    fun authorizedRequest(
        method: BrudcastHttpMethod,
        path: String,
        query: Map<String, String>,
        body: JSONObject?,
        retried: Boolean,
        completion: (BrudcastResult) -> Unit,
    ) {
        withSession { current, error ->
            if (current == null) {
                completion(BrudcastResult.Failure(error ?: BrudcastApiError(null, "no_session", "No session")))
                return@withSession
            }
            api.send(method, path, query, body, current.token) { result ->
                post {
                    if (result is BrudcastResult.Failure && result.error.statusCode == 401 && !retried) {
                        if (session == current) session = null
                        authorizedRequest(method, path, query, body, true, completion)
                        return@post
                    }
                    completion(result)
                }
            }
        }
    }

    fun enqueue(event: BrudcastEvent) {
        if (trackingDisabled) return
        event.owner = eventOwner
        val flushNow = events.enqueue(event)
        schedulePersist()
        if (flushNow) flush()
    }

    /**
     * Sends the next batch with the session of the user who queued it.
     *
     * Anonymous events go with whoever is signed in now, since an anonymous device that signs in is
     * the same person. Events of a user whose session is still opening wait for it. Events of a user
     * who has since signed out go with that user's last session while it lasts, and are dropped
     * after that rather than credited to someone else.
     */
    fun flush() {
        if (flushing) return
        val batch = events.nextBatch()
        if (batch == null) {
            drainFlushWaiters()
            return
        }
        val body = JSONObject().put("events", JSONArray(batch.map { it.toApiJson() }))
        val owner = batch.first().owner
        if (owner == null || owner == identity?.externalId) {
            flushing = true
            authorizedRequest(BrudcastHttpMethod.POST, "/events", emptyMap(), body, false) { result ->
                finishFlush(batch, result, null)
            }
            return
        }
        if (owner == eventOwner) {
            events.complete(batch, EventQueue.Outcome.UNAUTHORIZED)
            drainFlushWaiters()
            return
        }
        val retired = retiredSessions[owner]
        if (retired == null || isExpiring(retired)) {
            retiredSessions.remove(owner)
            BrudcastLogger.warning("${batch.size} events of a signed-out user outlived their session")
            events.complete(batch, EventQueue.Outcome.DISCARDED)
            flush()
            return
        }
        flushing = true
        api.send(BrudcastHttpMethod.POST, "/events", body = body, token = retired.token) { result ->
            post { finishFlush(batch, result, owner) }
        }
    }

    private fun finishFlush(batch: List<BrudcastEvent>, result: BrudcastResult, retiredOwner: String?) {
        flushing = false
        if (result is BrudcastResult.Success) {
            events.complete(batch, EventQueue.Outcome.SENT)
            flush()
            return
        }
        val error = (result as BrudcastResult.Failure).error
        when {
            error.statusCode == 401 && retiredOwner != null -> {
                retiredSessions.remove(retiredOwner)
                events.complete(batch, EventQueue.Outcome.DISCARDED)
                flush()
            }
            error.statusCode == 401 || error.code == "key_rejected" || error.code == "identity_rejected" -> {
                events.complete(batch, EventQueue.Outcome.UNAUTHORIZED)
                drainFlushWaiters()
            }
            error.isRetryable -> {
                events.complete(batch, EventQueue.Outcome.RETRY)
                drainFlushWaiters()
            }
            else -> {
                BrudcastLogger.warning("Events rejected: $error")
                val invalid = error.statusCode in INVALID_BATCH_STATUSES
                events.complete(batch, if (invalid) EventQueue.Outcome.REJECTED else EventQueue.Outcome.DISCARDED)
                flush()
            }
        }
    }

    private fun retire(current: BrudcastSession?) {
        retiredSessions.entries.removeAll { isExpiring(it.value) }
        val externalId = current?.externalId ?: return
        if (!isExpiring(current)) retiredSessions[externalId] = current
    }

    private fun schedulePersist() {
        if (persistScheduled) return
        persistScheduled = true
        schedule(PERSIST_DELAY_MS) {
            persistScheduled = false
            events.persistIfNeeded()
        }
    }

    private fun flushThen(timeoutMs: Long = 5_000, next: () -> Unit) {
        var done = false
        val finish = {
            if (!done) {
                done = true
                next()
            }
        }
        flushWaiters.add(finish)
        schedule(timeoutMs) { finish() }
        if (session == null) drainFlushWaiters() else flush()
    }

    private fun drainFlushWaiters() {
        val waiters = flushWaiters.toList()
        flushWaiters.clear()
        waiters.forEach { it() }
    }

    fun perform(action: BrudcastAction) {
        main.post {
            val handler = actionHandler ?: return@post DefaultActionPerformer.perform(application, activities.current, action)
            val decision = BrudcastDecision(
                fallback = BrudcastActionResult.USE_DEFAULT,
                timeoutMessage = "The action handler did not answer in time; the default behaviour runs",
            ) { result ->
                if (result == BrudcastActionResult.USE_DEFAULT) performDefault(action)
            }
            try {
                handler.handle(action, decision::decide)
            } catch (error: RuntimeException) {
                decision.fail(error, "action handler")
            }
        }
    }

    fun performDefault(action: BrudcastAction) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            DefaultActionPerformer.perform(application, activities.current, action)
        } else {
            main.post { DefaultActionPerformer.perform(application, activities.current, action) }
        }
    }

    fun attachActivity(activity: android.app.Activity) {
        main.post {
            if (activity.isFinishing || activity.isDestroyed || activities.current === activity) return@post
            activities.onActivityResumed(activity)
        }
    }

    private fun appDidBecomeActive() {
        if (isAppActive) return
        isAppActive = true
        keyRejected = false
        flushTimer?.cancel(false)
        flushTimer = executor.scheduleWithFixedDelay({ post { flush() } }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
        if (session == null || isExpiring(session)) {
            openSession { _, _ -> }
        } else {
            modules.forEach { it.appDidBecomeActive() }
        }
        flush()
    }

    private fun appDidEnterBackground() {
        isAppActive = false
        events.persistIfNeeded()
        flushTimer?.cancel(false)
        flushTimer = null
        modules.forEach { it.appDidEnterBackground() }
        flush()
    }

    private fun appVersion(): String? = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            application.packageManager.getPackageInfo(application.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            application.packageManager.getPackageInfo(application.packageName, 0)
        }
        info.versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun loadIdentity(): BrudcastIdentity? {
        val json = storage.readObject(IDENTITY_FILE) ?: return null
        val externalId = json.optString("externalId").ifEmpty { return null }
        return BrudcastIdentity(
            externalId,
            if (json.isNull("userHash")) null else json.optString("userHash").ifEmpty { null },
            BrudcastTraits.fromJson(json.optJSONObject("traits")),
        )
    }

    private fun persistIdentity() {
        val current = identity
        if (current == null) {
            storage.remove(IDENTITY_FILE)
            return
        }
        val json = JSONObject().put("externalId", current.externalId)
        current.userHash?.let { json.put("userHash", it) }
        current.traits?.let { json.put("traits", it.toJson()) }
        storage.write(IDENTITY_FILE, json)
    }

    companion object {
        private val EVENT_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_.:-]{0,63}$")
        private val HASH_PATTERN = Regex("^[0-9a-fA-F]{64}$")
        private const val TRACKING_DISABLED_KEY = "brudcast.tracking_disabled"
        private const val IDENTITY_FILE = "identity.json"
        private const val FLUSH_INTERVAL_MS = 10_000L
        private const val REFRESH_LEEWAY_MS = 60_000L
        private const val MAX_SESSION_BACKOFF_MS = 300_000L
        private const val PERSIST_DELAY_MS = 1_000L
        private val INVALID_BATCH_STATUSES = setOf(400, 413, 422)

        fun isValidEventName(name: String): Boolean = EVENT_NAME_PATTERN.matches(name)

        /**
         * Tells a refused publishable key apart from a refused user hash on a 401 from `/sessions`.
         *
         * The service's `code` decides when it sends one. Otherwise a request without an identity
         * can only have failed on its key, and one with an identity failed on its key only when the
         * message names the key.
         *
         * @param error The 401.
         * @param hadIdentity Whether the request carried an external id.
         * @return True when the key was refused.
         */
        fun isKeyRejection(error: BrudcastApiError, hadIdentity: Boolean): Boolean = when (error.code) {
            "key_rejected" -> true
            "identity_rejected" -> false
            else -> !hadIdentity || error.message.contains("publishable key", ignoreCase = true)
        }

        fun parseSession(payload: JSONObject): BrudcastSession? {
            val sessionJson = payload.optJSONObject("session") ?: return null
            val token = sessionJson.optString("token").ifEmpty { return null }
            val expiresAt = BrudcastDates.parse(sessionJson.optString("expiresAt")) ?: return null
            val user = payload.optJSONObject("user") ?: return null
            val userId = user.optString("id").ifEmpty { return null }
            val serverTime = BrudcastDates.parse(payload.optString("serverTime"))
            return BrudcastSession(
                token = token,
                expiresAt = expiresAt,
                userId = userId,
                externalId = if (user.isNull("externalId")) null else user.optString("externalId").ifEmpty { null },
                verified = user.optBoolean("verified", false),
                optedOut = user.optBoolean("optedOut", false),
                serverTimeOffset = serverTime?.let { it - System.currentTimeMillis() } ?: 0,
            )
        }
    }
}
