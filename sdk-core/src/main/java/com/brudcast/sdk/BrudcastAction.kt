package com.brudcast.sdk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Something a message asked the app to do: open a deep link or a URL, or handle a custom event.
 */
public data class BrudcastAction(
    val kind: Kind,
    val value: String,
    val opensInApp: Boolean = false,
    val source: Source,
) {
    public enum class Kind { DEEPLINK, URL, CUSTOM_EVENT }

    public sealed class Source {
        public data class InApp(val messageId: String, val buttonId: String?) : Source()
        public data class Push(val messageId: String) : Source()
    }
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
                launcher.startActivity(own)
                return
            }
        }
        try {
            launcher.startActivity(view)
        } catch (_: ActivityNotFoundException) {
            BrudcastLogger.warning("No app could open ${action.value}")
        }
    }
}
