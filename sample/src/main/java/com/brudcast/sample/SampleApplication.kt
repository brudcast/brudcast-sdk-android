package com.brudcast.sample

import android.app.Application
import android.util.Log
import androidx.core.net.toUri
import com.brudcast.sdk.Brudcast
import com.brudcast.sdk.BrudcastAction
import com.brudcast.sdk.BrudcastActionResult
import com.brudcast.sdk.BrudcastEnvironment
import com.brudcast.sdk.BrudcastLogLevel
import com.brudcast.sdk.BrudcastOptions
import com.brudcast.sdk.inapp.InAppModule
import com.brudcast.sdk.push.PushModule
import com.brudcast.sdk.push.firebase.BrudcastPushFirebase
import com.google.firebase.FirebaseApp

/**
 * Starts the SDK the way an app should: once, in `Application.onCreate`, with the publishable key
 * and environment from `local.properties`.
 */
class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SampleSession.load(this)
        val key = BuildConfig.BRUDCAST_PUBLISHABLE_KEY
        if (key.isBlank()) {
            Log.w(TAG, "Set brudcast.publishableKey in local.properties to start the SDK")
            return
        }
        PushModule.smallIcon = R.drawable.ic_notification
        Brudcast.setActionHandler(::handleAction)
        Brudcast.initialize(
            this,
            key,
            BrudcastOptions(
                environment = environment(),
                logLevel = BrudcastLogLevel.DEBUG,
                modules = listOf(InAppModule, PushModule),
                accentColor = BRAND_PRIMARY,
            ),
        )
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            BrudcastPushFirebase.register()
        } else {
            Log.i(TAG, "No google-services.json, so push tokens are not registered")
        }
    }

    /**
     * Records every action in the activity log, and routes the sample's own deep links to its
     * screens, such as `brudcast-sample://checkout`.
     *
     * @param action The action from an in-app button or a push tap.
     * @return [BrudcastActionResult.HANDLED] for `brudcast-sample:` links, the default behaviour
     *   for everything else.
     */
    private fun handleAction(action: BrudcastAction): BrudcastActionResult {
        ActionLog.record(action)
        val ownLink = action.kind == BrudcastAction.Kind.DEEPLINK && action.value.startsWith("$DEEP_LINK_SCHEME:")
        if (!ownLink) return BrudcastActionResult.USE_DEFAULT
        SampleRouter.open(action.value.toUri())
        return BrudcastActionResult.HANDLED
    }

    private fun environment(): BrudcastEnvironment = when (BuildConfig.BRUDCAST_ENVIRONMENT.lowercase()) {
        "staging" -> BrudcastEnvironment.Staging
        "uat" -> BrudcastEnvironment.Uat
        "development" -> BrudcastEnvironment.Development
        "local" -> BrudcastEnvironment.Local(host = BuildConfig.BRUDCAST_LOCAL_HOST)
        else -> BrudcastEnvironment.Production
    }

    companion object {
        const val TAG = "BrudcastSample"
        const val DEEP_LINK_SCHEME = "brudcast-sample"

        /** The sample's primary blue, for buttons in messages. */
        const val BRAND_PRIMARY = 0xFF365AED.toInt()
    }
}
