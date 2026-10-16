package com.brudcast.sdk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Something a message asked the app to do: open a deep link or a URL, or handle a custom event.
 *
 * @property kind What to do.
 * @property value The link, or the custom event's name.
 * @property opensInApp Whether a web link should open in a Custom Tab rather than the browser.
 * @property source The message and button the action came from.
 * @property verified False when the action came from a push tap the SDK cannot prove it created,
 *   such as one on a notification FCM drew itself, which another app could also forge. The default
 *   behaviour then only opens what another app could open directly: web links and exported
 *   activities, never this app's private ones. Check it before routing a deep link yourself.
 */
public class BrudcastAction(
    public val kind: Kind,
    public val value: String,
    public val opensInApp: Boolean = false,
    public val source: Source,
    public val verified: Boolean = true,
) {
    public enum class Kind { DEEPLINK, URL, CUSTOM_EVENT }

    public sealed class Source {
        public data class InApp(val messageId: String, val buttonId: String?) : Source()
        public data class Push(val messageId: String) : Source()
    }

    override fun equals(other: Any?): Boolean =
        other is BrudcastAction && kind == other.kind && value == other.value && opensInApp == other.opensInApp &&
            source == other.source && verified == other.verified

    override fun hashCode(): Int = listOf(kind, value, opensInApp, source, verified).hashCode()

    override fun toString(): String = "BrudcastAction(kind=$kind, value=$value, opensInApp=$opensInApp, source=$source, verified=$verified)"
}

public enum class BrudcastActionResult { HANDLED, USE_DEFAULT }

/**
 * Decides what to do with an action before the default behaviour. Called on the main thread.
 */
public fun interface BrudcastActionHandler {
    public fun handle(action: BrudcastAction): BrudcastActionResult
}

internal object DefaultActionPerformer {
    fun perform(context: Context, activity: Activity?, action: BrudcastAction) {
        if (action.kind == BrudcastAction.Kind.CUSTOM_EVENT) return
        val uri = try {
            Uri.parse(action.value)
        } catch (_: RuntimeException) {
            null
        }
        if (uri?.scheme.isNullOrEmpty()) {
            BrudcastLogger.warning("Ignoring an action with an invalid URL: ${action.value}")
            return
        }
        val launcher: Context = activity ?: context
        val isWeb = uri!!.scheme.equals("http", true) || uri.scheme.equals("https", true)
        if (action.kind == BrudcastAction.Kind.URL && action.opensInApp && isWeb && activity != null) {
            try {
                CustomTabsIntent.Builder().build().launchUrl(activity, uri)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        val view = Intent(Intent.ACTION_VIEW, uri)
        if (activity == null) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (action.kind == BrudcastAction.Kind.DEEPLINK) {
            val own = Intent(view).setPackage(context.packageName)
            if (own.resolveActivity(context.packageManager) != null) {
                if (!action.verified && !opensExported(context, own)) {
                    BrudcastLogger.warning("Ignoring an unverified push link to a private activity: ${action.value}")
                    return
                }
                start(launcher, own, action)
                return
            }
        }
        if (!action.verified && !opensExported(context, view)) {
            BrudcastLogger.warning("Ignoring an unverified push link to a private activity: ${action.value}")
            return
        }
        start(launcher, view, action)
    }

    /**
     * Tells whether an intent lands somewhere any app could reach directly: another app's activity,
     * or an exported one of this app.
     *
     * @param context Any context.
     * @param intent The intent.
     * @return True when it resolves to such an activity.
     */
    private fun opensExported(context: Context, intent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val info = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return true
        return info.packageName != context.packageName || info.exported
    }

    private fun start(launcher: Context, intent: Intent, action: BrudcastAction) {
        try {
            launcher.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            BrudcastLogger.warning("No app could open ${action.value}")
        } catch (error: SecurityException) {
            BrudcastLogger.warning("Not allowed to open ${action.value}: ${error.message}")
        }
    }
}
