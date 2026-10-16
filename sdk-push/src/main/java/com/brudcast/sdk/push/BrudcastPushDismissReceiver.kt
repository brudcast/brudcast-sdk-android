package com.brudcast.sdk.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Records `push_dismissed` when the user swipes away a notification the SDK posted.
 */
public class BrudcastPushDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        val data = HashMap<String, String>()
        for (key in extras.keySet()) {
            extras.getString(key)?.let { data[key] = it }
        }
        PushModule.dismissed(data)
    }
}
