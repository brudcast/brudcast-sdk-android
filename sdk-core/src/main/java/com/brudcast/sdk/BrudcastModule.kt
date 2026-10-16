package com.brudcast.sdk

import android.app.Activity
import android.app.Application
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService

/**
 * The session the service issued to this device.
 *
 * @property serverTimeOffset The service's clock minus the device's, in milliseconds.
 */
public class BrudcastSession(
    public val token: String,
    public val expiresAt: Long,
    public val userId: String,
    public val externalId: String?,
    public val verified: Boolean,
    public val optedOut: Boolean,
    public val serverTimeOffset: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is BrudcastSession &&
            token == other.token &&
            expiresAt == other.expiresAt &&
            userId == other.userId &&
            externalId == other.externalId &&
            verified == other.verified &&
            optedOut == other.optedOut &&
            serverTimeOffset == other.serverTimeOffset

    override fun hashCode(): Int = listOf<Any?>(token, expiresAt, userId, externalId, verified, optedOut, serverTimeOffset).hashCode()

    override fun toString(): String = "BrudcastSession(expiresAt=$expiresAt, userId=$userId, externalId=$externalId, verified=$verified, optedOut=$optedOut, serverTimeOffset=$serverTimeOffset)"

    /** The current time on the service's clock, as estimated when the session opened. */
    public fun serverNow(): Long = System.currentTimeMillis() + serverTimeOffset
}

/**
 * A feature module the core runs, such as in-app messaging or push.
 *
 * Every method is called on the core's single thread. A module that touches UI posts to the main
 * thread itself.
 */
public interface BrudcastModule {
    /** The name sent in the session's `modules` list: `in_app` or `push`. */
    public val name: String

    /** Called once, when the SDK is initialised. */
    public fun attach(context: BrudcastContext)

    /** Called every time a session opens, with the whole session response. */
    public fun sessionDidOpen(session: BrudcastSession, payload: JSONObject) {}

    /** Called before `reset()` drops the user, while the old session still works. */
    public fun sessionWillReset(session: BrudcastSession, completion: () -> Unit) {
        completion()
    }

    /** Called after `reset()` dropped the user, before the anonymous session opens. */
    public fun didReset() {}

    /** Called when the app comes to the foreground and a session is open. */
    public fun appDidBecomeActive() {}

    /** Called when the app moves to the background. */
    public fun appDidEnterBackground() {}

    /** Called for every `track` call. */
    public fun didTrack(name: String, properties: Map<String, BrudcastValue>) {}

    /** Called for every `screen` call. */
    public fun didScreen(name: String, properties: Map<String, BrudcastValue>) {}

    /** Called when an activity resumes, so modules can read its intent. */
    public fun activityResumed(activity: Activity) {}
}

/**
 * Implemented by the push module so the in-app `request_push_permission` action can reach it.
 */
public interface BrudcastPushPermissionRequesting {
    /**
     * Asks the user for notification permission.
     *
     * @param activity The activity to ask from.
     * @param completion Called on the main thread with whether notifications are now allowed.
     */
    public fun requestPushPermission(activity: Activity, completion: (Boolean) -> Unit)
}

/**
 * What a module can use from the core.
 */
public class BrudcastContext internal constructor(
    private val core: BrudcastCore,
    public val application: Application,
    public val options: BrudcastOptions,
    public val storage: BrudcastStorage,
    public val api: BrudcastApiClient,
    public val executor: ScheduledExecutorService,
    public val deviceId: String,
) {
    /** The open session, or null while none is open. */
    public val session: BrudcastSession? get() = core.session

    /** True while the app is in the foreground. */
    public val isAppActive: Boolean get() = core.isAppActive

    /** The resumed activity, if any. */
    public val currentActivity: Activity? get() = core.activities.current

    /**
     * Sends an authenticated request. When no session is open one is opened first, and a 401
     * reopens the session and retries once. The completion runs on the core thread.
     *
     * @param method The HTTP method.
     * @param path The path after `/sdk/v1`.
     * @param query Query parameters.
     * @param body A JSON body, or null.
     * @param completion Called with the result.
     */
    public fun request(
        method: BrudcastHttpMethod,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        completion: (BrudcastResult) -> Unit,
    ) {
        core.authorizedRequest(method, path, query, body, retried = false, completion = completion)
    }

    /**
     * Queues an event for sending, unless tracking is disabled.
     *
     * @param event The event.
     */
    public fun enqueue(event: BrudcastEvent) {
        core.enqueue(event)
    }

    /** Sends waiting events now. */
    public fun flush() {
        core.flush()
    }

    /**
     * Runs an action through the host's action handler, then the default behaviour.
     *
     * @param action The action.
     */
    public fun perform(action: BrudcastAction) {
        core.perform(action)
    }

    /**
     * Runs a task on the core thread.
     *
     * @param task The task.
     */
    public fun post(task: () -> Unit) {
        core.post(task)
    }

    /**
     * Finds another installed module.
     *
     * @return The first module of the type, or null.
     */
    public inline fun <reified T> module(): T? = modules.filterIsInstance<T>().firstOrNull()

    public val modules: List<BrudcastModule> get() = core.modules
}

internal class ActivityTracker : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var resumed: java.lang.ref.WeakReference<Activity>? = null
    var onResumed: ((Activity) -> Unit)? = null

    val current: Activity? get() = resumed?.get()

    override fun onActivityResumed(activity: Activity) {
        resumed = java.lang.ref.WeakReference(activity)
        onResumed?.invoke(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
