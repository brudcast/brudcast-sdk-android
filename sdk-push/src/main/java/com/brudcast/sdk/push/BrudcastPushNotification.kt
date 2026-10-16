package com.brudcast.sdk.push

import android.os.Bundle

/** The data keys Brudcast adds to every push notification. */
public object BrudcastPushKeys {
    public const val MESSAGE_ID: String = "bc_mid"
    public const val CAMPAIGN_ID: String = "bc_cid"
    public const val APPLICATION_ID: String = "bc_app"
    public const val CLICK_URL: String = "click_url"

    internal val reserved = setOf(MESSAGE_ID, CAMPAIGN_ID, APPLICATION_ID, CLICK_URL)
}

/**
 * A push notification Brudcast sent, read from its data.
 *
 * @property data The custom data the campaign carried, without the Brudcast keys.
 */
public class BrudcastPushNotification(
    public val messageId: String,
    public val campaignId: String?,
    public val applicationId: String?,
    public val title: String?,
    public val body: String?,
    public val imageUrl: String?,
    public val clickUrl: String?,
    public val data: Map<String, String>,
) {
    override fun equals(other: Any?): Boolean =
        other is BrudcastPushNotification &&
            messageId == other.messageId &&
            campaignId == other.campaignId &&
            applicationId == other.applicationId &&
            title == other.title &&
            body == other.body &&
            imageUrl == other.imageUrl &&
            clickUrl == other.clickUrl &&
            data == other.data

    override fun hashCode(): Int = listOf<Any?>(messageId, campaignId, applicationId, title, body, imageUrl, clickUrl, data).hashCode()

    override fun toString(): String = "BrudcastPushNotification(messageId=$messageId, campaignId=$campaignId, applicationId=$applicationId, title=$title, body=$body, imageUrl=$imageUrl, clickUrl=$clickUrl, data=$data)"

    public companion object {
        /**
         * Reads a notification's data.
         *
         * @param data The FCM data map.
         * @param title The notification title, when FCM sent one.
         * @param body The notification body.
         * @param imageUrl The notification image.
         * @return The notification, or null when the data has no `bc_mid` and so is not from Brudcast.
         */
        @JvmStatic
        @JvmOverloads
        public fun from(data: Map<String, String>, title: String? = null, body: String? = null, imageUrl: String? = null): BrudcastPushNotification? {
            val messageId = data[BrudcastPushKeys.MESSAGE_ID]?.takeIf { it.isNotEmpty() } ?: return null
            return BrudcastPushNotification(
                messageId = messageId,
                campaignId = data[BrudcastPushKeys.CAMPAIGN_ID],
                applicationId = data[BrudcastPushKeys.APPLICATION_ID],
                title = title ?: data["title"],
                body = body ?: data["body"],
                imageUrl = imageUrl ?: data["image"],
                clickUrl = data[BrudcastPushKeys.CLICK_URL]?.takeIf { it.isNotEmpty() },
                data = data.filterKeys { it !in BrudcastPushKeys.reserved && !it.startsWith("google.") && !it.startsWith("gcm.") },
            )
        }

        /**
         * Reads the extras FCM put on the launch intent when the user tapped a notification shown
         * while the app was in the background.
         *
         * @param extras The intent extras.
         * @return The notification, or null when the extras are not from a Brudcast notification.
         */
        private const val SDK_EXTRA_PREFIX = "com.brudcast.sdk."

        @JvmStatic
        public fun fromExtras(extras: Bundle?): BrudcastPushNotification? {
            extras ?: return null
            val data = HashMap<String, String>()
            for (key in extras.keySet()) {
                @Suppress("DEPRECATION")
                val value = extras.get(key)
                if (value is String && !key.startsWith(SDK_EXTRA_PREFIX)) data[key] = value
            }
            return from(data)
        }
    }
}
