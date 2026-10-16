package com.brudcast.sdk.push.firebase

import android.content.Context
import com.brudcast.sdk.BrudcastLogger
import com.brudcast.sdk.push.BrudcastPushNotification
import com.brudcast.sdk.push.PushModule
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Connects Firebase Messaging to the Brudcast push module. Firebase is a compile-only dependency,
 * so the app's own Firebase version is the one that runs.
 */
public object BrudcastPushFirebase {
    /**
     * Fetches the current FCM token and passes it to the SDK. Call it once after `initialize`.
     */
    @JvmStatic
    public fun register() {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> PushModule.setToken(token) }
            .addOnFailureListener { error -> BrudcastLogger.warning("Could not read the FCM token: ${error.message}") }
    }

    /**
     * Passes a refreshed token to the SDK. Call it from your service's `onNewToken`.
     *
     * @param token The new FCM token.
     */
    @JvmStatic
    public fun onNewToken(token: String) {
        PushModule.setToken(token)
    }

    /**
     * Handles a message when it is from Brudcast. Call it from your service's `onMessageReceived`.
     *
     * @param context The service.
     * @param message The message.
     * @return True when the message was from Brudcast and the SDK handled it; false to handle it yourself.
     */
    @JvmStatic
    public fun onMessageReceived(context: Context, message: RemoteMessage): Boolean {
        val payload = message.notification
        val notification = BrudcastPushNotification.from(
            message.data,
            payload?.title,
            payload?.body,
            payload?.imageUrl?.toString(),
        ) ?: return false
        return PushModule.onMessageReceived(context, notification)
    }
}

/**
 * A `FirebaseMessagingService` that hands tokens and Brudcast messages to the SDK. Register it in
 * your manifest, or subclass it and override [onOtherMessage] for messages from other senders. If
 * you already have a service, call [BrudcastPushFirebase] from it instead.
 */
public open class BrudcastFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        BrudcastPushFirebase.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!BrudcastPushFirebase.onMessageReceived(this, message)) onOtherMessage(message)
    }

    /**
     * Called for messages that are not from Brudcast.
     *
     * @param message The message.
     */
    public open fun onOtherMessage(message: RemoteMessage) {}
}
