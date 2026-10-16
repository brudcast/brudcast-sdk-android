package com.brudcast.sdk.push

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.brudcast.sdk.Brudcast
import com.brudcast.sdk.BrudcastAction
import com.brudcast.sdk.BrudcastContext
import com.brudcast.sdk.BrudcastEvent
import com.brudcast.sdk.BrudcastHttpMethod
import com.brudcast.sdk.BrudcastLogLevel
import com.brudcast.sdk.BrudcastLogger
import com.brudcast.sdk.BrudcastModule
import com.brudcast.sdk.BrudcastPushPermissionRequesting
import com.brudcast.sdk.BrudcastResult
import com.brudcast.sdk.BrudcastSession
import com.brudcast.sdk.BrudcastValue
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/** Whether the app may show notifications. */
public enum class BrudcastPushPermission(public val wire: String) {
    NOT_DETERMINED("not_determined"), DENIED("denied"), AUTHORIZED("authorized");

    internal companion object {
        fun from(value: String?): BrudcastPushPermission? = entries.firstOrNull { it.wire == value }
    }
}

/** What the host wants done with a Brudcast notification that arrives while the app is open. */
public enum class BrudcastNotificationDecision { SHOW, SUPPRESS }

/** Decides whether a notification arriving in the foreground shows. Called on the main thread. */
public fun interface BrudcastNotificationHandler {
    public fun handle(notification: BrudcastPushNotification): BrudcastNotificationDecision
}

/** Push notifications. Add [PushModule] to `BrudcastOptions.modules` to run it. */
public val Brudcast.push: PushModule
    get() = PushModule

/**
 * Push notifications: permission, token registration, display and open tracking.
 *
 * Push sends through FCM. Pass the FCM registration token to [setToken], or use the
 * `sdk-push-firebase` adapter, which does it for you.
 */
public object PushModule : BrudcastModule, BrudcastPushPermissionRequesting {
    override val name: String = "push"

    internal const val EVENT_CHANNEL = "push"
    internal const val EXTRA_HANDLED = "com.brudcast.sdk.push.HANDLED"
    internal const val EXTRA_FROM_SDK = "com.brudcast.sdk.push.FROM_SDK"
    private const val TOKEN_KEY = "brudcast.push.token"
    private const val REGISTERED_KEY = "brudcast.push.registered"
    private const val PERMISSION_KEY = "brudcast.push.permission"
    private const val REQUEST_CODE = 0xB0C
    private const val CHANNEL_ID = "brudcast_default"

    private val main = Handler(Looper.getMainLooper())
    private var context: BrudcastContext? = null
    private var token: String? = null
    private var permission = BrudcastPushPermission.NOT_DETERMINED
    private val permissionState = kotlinx.coroutines.flow.MutableStateFlow(BrudcastPushPermission.NOT_DETERMINED)
    private var linkMissing = false
    private var routeMissing = false
    private var registering = false
    private var failures = 0
    private var retry: ScheduledFuture<*>? = null
    private var pendingPermission: ((Boolean) -> Unit)? = null
    private var asked = false

    @Volatile
    private var notificationHandler: BrudcastNotificationHandler? = null

    /** The name of the notification channel the SDK posts to. */
    @Volatile
    public var channelName: String = "Notifications"

    /** The small icon of notifications the SDK posts. The app icon is used when zero. */
    @Volatile
    public var smallIcon: Int = 0

    /** The current permission status. */
    public val permissionStatus: kotlinx.coroutines.flow.StateFlow<BrudcastPushPermission> = permissionState

    override fun attach(context: BrudcastContext) {
        this.context = context
        token = context.storage.string(TOKEN_KEY)
        asked = context.storage.bool("brudcast.push.asked")
        permission = BrudcastPushPermission.from(context.storage.string(PERMISSION_KEY)) ?: BrudcastPushPermission.NOT_DETERMINED
        permissionState.value = permission
        refreshPermission()
    }

    override fun sessionDidOpen(session: BrudcastSession, payload: JSONObject) {
        routeMissing = false
        register(force = false)
    }

    override fun sessionWillReset(session: BrudcastSession, completion: () -> Unit) {
        val context = context
        if (context == null || token == null || linkMissing) {
            completion()
            return
        }
        context.request(BrudcastHttpMethod.DELETE, "/push-token") { result ->
            if (result is BrudcastResult.Failure && result.error.statusCode != 404) {
                BrudcastLogger.info("Could not detach the push token: ${result.error}")
            }
            context.storage.setString(REGISTERED_KEY, null)
            completion()
        }
    }

    override fun appDidBecomeActive() {
        refreshPermission()
    }

    override fun activityResumed(activity: Activity) {
        val callback = pendingPermission
        if (callback != null) {
            pendingPermission = null
            refreshPermission()
            val granted = currentPermission(activity) == BrudcastPushPermission.AUTHORIZED
            main.post { callback(granted) }
        }
        handleIntent(activity.intent)
    }

    /**
     * Stores the FCM registration token and registers it for this user.
     *
     * @param token The FCM registration token.
     */
    public fun setToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            BrudcastLogger.error("setToken was given an empty token")
            return
        }
        Brudcast.withContext { context ->
            if (this.token == trimmed) return@withContext
            this.token = trimmed
            context.storage.setString(TOKEN_KEY, trimmed)
            register(force = false)
        }
    }

    /**
     * Asks for permission to show notifications. On Android 12 and older notifications are allowed
     * unless the user turned them off, so this only reports the status.
     *
     * @param activity The activity to ask from.
     * @param completion Called on the main thread with whether notifications are allowed.
     */
    public fun requestPermission(activity: Activity, completion: (Boolean) -> Unit = {}) {
        main.post {
            if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                refreshPermission()
                completion(NotificationManagerCompat.from(activity).areNotificationsEnabled())
                return@post
            }
            Brudcast.withContext { context ->
                pendingPermission = completion
                asked = true
                context.storage.setBool("brudcast.push.asked", true)
            }
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
        }
    }

    override fun requestPushPermission(activity: Activity, completion: (Boolean) -> Unit) {
        requestPermission(activity, completion)
    }

    /**
     * Sets the handler asked when a Brudcast notification arrives while the app is open.
     *
     * @param handler The handler, or null to show every notification.
     */
    public fun setNotificationHandler(handler: BrudcastNotificationHandler?) {
        notificationHandler = handler
    }

    /**
     * Tells whether FCM data came from Brudcast.
     *
     * @param data The data map.
     * @return True when it carries a Brudcast message id.
     */
    public fun isBrudcastNotification(data: Map<String, String>): Boolean = BrudcastPushNotification.from(data) != null

    /**
     * Handles a message FCM delivered to the app: records that it arrived and, when FCM did not
     * display it, shows it. Call it from `FirebaseMessagingService.onMessageReceived`, or use
     * `BrudcastPushFirebase`.
     *
     * @param context Any context.
     * @param notification The notification, from [BrudcastPushNotification.from].
     * @return True when the notification was from Brudcast and was handled.
     */
    public fun onMessageReceived(context: Context, notification: BrudcastPushNotification): Boolean {
        Brudcast.withContext {
            it.enqueue(event("push_received", notification))
        }
        main.post {
            val decision = try {
                notificationHandler?.handle(notification) ?: BrudcastNotificationDecision.SHOW
            } catch (error: RuntimeException) {
                BrudcastLogger.error("The notification handler threw: ${error.message}")
                BrudcastNotificationDecision.SHOW
            }
            if (decision == BrudcastNotificationDecision.SHOW) {
                Thread { show(context.applicationContext, notification) }.start()
            }
        }
        return true
    }

    /**
     * Records a tap on a notification and runs its click URL. The SDK calls it for every resumed
     * activity; call it yourself from `onNewIntent` when your activity is `singleTop`.
     *
     * @param intent The intent that opened the activity.
     * @return True when the intent came from a Brudcast notification not handled before.
     */
    public fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        if (intent.getBooleanExtra(EXTRA_HANDLED, false)) return false
        val notification = BrudcastPushNotification.fromExtras(intent.extras) ?: return false
        intent.putExtra(EXTRA_HANDLED, true)
        val clickUrl = notification.clickUrl
        Brudcast.withContext { context ->
            context.enqueue(event("push_opened", notification, mapOf("clicked" to BrudcastValue.Bool(clickUrl != null))))
            context.flush()
            if (clickUrl != null) {
                val isWeb = clickUrl.startsWith("http://", true) || clickUrl.startsWith("https://", true)
                context.perform(
                    BrudcastAction(
                        if (isWeb) BrudcastAction.Kind.URL else BrudcastAction.Kind.DEEPLINK,
                        clickUrl,
                        opensInApp = false,
                        source = BrudcastAction.Source.Push(notification.messageId),
                    ),
                )
            }
        }
        return true
    }

    internal fun dismissed(data: Map<String, String>) {
        val notification = BrudcastPushNotification.from(data) ?: return
        Brudcast.withContext { it.enqueue(event("push_dismissed", notification)) }
    }

    private fun event(type: String, notification: BrudcastPushNotification, extra: Map<String, BrudcastValue> = emptyMap()): BrudcastEvent {
        val properties = HashMap(extra)
        notification.campaignId?.let { properties["campaignId"] = BrudcastValue.Text(it) }
        return BrudcastEvent(type = type, messageId = notification.messageId, properties = properties, channel = EVENT_CHANNEL)
    }

    private fun show(context: Context, notification: BrudcastPushNotification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            val system = context.getSystemService(NotificationManager::class.java)
            if (system.getNotificationChannel(CHANNEL_ID) == null) {
                system.createNotificationChannel(NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_DEFAULT))
            }
        }
        val extras = android.os.Bundle()
        val data = HashMap(notification.data)
        data[BrudcastPushKeys.MESSAGE_ID] = notification.messageId
        notification.campaignId?.let { data[BrudcastPushKeys.CAMPAIGN_ID] = it }
        notification.applicationId?.let { data[BrudcastPushKeys.APPLICATION_ID] = it }
        notification.clickUrl?.let { data[BrudcastPushKeys.CLICK_URL] = it }
        data.forEach { (key, value) -> extras.putString(key, value) }

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtras(extras)
            putExtra(EXTRA_FROM_SDK, true)
        } ?: return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val requestCode = notification.messageId.hashCode()
        val content = PendingIntent.getActivity(context, requestCode, launch, flags)
        val delete = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, BrudcastPushDismissReceiver::class.java).putExtras(extras),
            flags,
        )
        val image = notification.imageUrl?.let(::download)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (smallIcon != 0) smallIcon else context.applicationInfo.icon)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setAutoCancel(true)
            .setContentIntent(content)
            .setDeleteIntent(delete)
        if (image != null) {
            builder.setLargeIcon(image).setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).bigLargeIcon(null as Bitmap?))
        } else if (notification.body != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
        }
        try {
            manager.notify(notification.messageId, 0, builder.build())
        } catch (error: SecurityException) {
            BrudcastLogger.warning("Notification permission is missing: ${error.message}")
        }
    }

    private fun download(url: String): Bitmap? {
        if (!url.startsWith("https://", true)) return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            if (connection.responseCode in 200..299) connection.inputStream.use(BitmapFactory::decodeStream) else null
        } catch (_: IOException) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun currentPermission(context: Context): BrudcastPushPermission {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (enabled) return BrudcastPushPermission.AUTHORIZED
        if (Build.VERSION.SDK_INT >= 33 && !asked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return BrudcastPushPermission.NOT_DETERMINED
        }
        return BrudcastPushPermission.DENIED
    }

    private fun refreshPermission() {
        val context = context ?: return
        context.post {
            val status = currentPermission(context.application)
            if (status == permission) return@post
            val previous = permission
            permission = status
            permissionState.value = status
            context.storage.setString(PERMISSION_KEY, status.wire)
            context.enqueue(
                BrudcastEvent(
                    type = "push_permission",
                    name = status.wire,
                    properties = mapOf("previous" to BrudcastValue.Text(previous.wire)),
                    channel = EVENT_CHANNEL,
                ),
            )
            register(force = true)
        }
    }

    private fun register(force: Boolean) {
        val context = context ?: return
        val token = token ?: return
        val session = context.session ?: return
        if (linkMissing || routeMissing || registering) return
        val signature = listOf(token, permission.wire, session.userId, if (session.verified) "v" else "u").joinToString("|")
        if (!force && context.storage.string(REGISTERED_KEY) == signature) return
        registering = true
        retry?.cancel(false)
        val body = JSONObject().put("token", token).put("provider", "fcm").put("permission", permission.wire)
        appVersion(context.application)?.let { body.put("appVersion", it) }
        context.request(BrudcastHttpMethod.PUT, "/push-token", body = body) { result ->
            registering = false
            when (result) {
                is BrudcastResult.Success -> {
                    failures = 0
                    context.storage.setString(REGISTERED_KEY, signature)
                    BrudcastLogger.info("Push token registered")
                }
                is BrudcastResult.Failure -> {
                    val error = result.error
                    when {
                        error.statusCode == 409 -> {
                            linkMissing = true
                            BrudcastLogger.error("Push is not linked for this app: link a push application to the in-app application in the dashboard. (${error.message})")
                        }
                        error.statusCode == 404 -> {
                            routeMissing = true
                            BrudcastLogger.logOnce("push-route", BrudcastLogLevel.WARNING) {
                                "This Brudcast environment does not accept device push tokens yet; register tokens from your backend until it does"
                            }
                        }
                        error.isRetryable -> {
                            failures += 1
                            val delay = min(300_000.0, 2_000 * 2.0.pow(failures - 1)).toLong()
                            retry = context.executor.schedule({ context.post { register(force) } }, delay, TimeUnit.MILLISECONDS)
                        }
                        else -> BrudcastLogger.error("The push token was refused: $error")
                    }
                }
            }
        }
    }

    private fun appVersion(context: Context): String? = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
