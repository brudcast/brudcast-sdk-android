package com.brudcast.sdk

import org.json.JSONObject
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * One event bound for `POST /sdk/v1/events`.
 *
 * @property type The event type, such as `impression`.
 * @property messageId The message the event is about, when it is about one.
 * @property buttonId The tapped button, for clicks.
 * @property name The event or screen name, for `custom` and `screen` events.
 * @property properties Event properties.
 * @property occurredAt When it happened, in epoch milliseconds.
 * @property channel Which batch the event travels in. Events of different channels never share a
 *   batch, so a type one deployment of the service does not accept cannot fail the others.
 * @property id The client id the service deduplicates on.
 */
public data class BrudcastEvent(
    val type: String,
    val messageId: String? = null,
    val buttonId: String? = null,
    val name: String? = null,
    val properties: Map<String, BrudcastValue> = emptyMap(),
    val occurredAt: Long = System.currentTimeMillis(),
    val channel: String = CORE_CHANNEL,
    val id: String = UUID.randomUUID().toString(),
) {
    internal fun toApiJson(): JSONObject {
        val json = JSONObject()
            .put("id", id)
            .put("type", type)
            .put("occurredAt", BrudcastDates.format(occurredAt))
        messageId?.let { json.put("messageId", it) }
        buttonId?.let { json.put("buttonId", it) }
        name?.let { json.put("name", it) }
        if (properties.isNotEmpty()) json.put("properties", BrudcastValue.propertiesToJson(properties))
        return json
    }

    internal fun toStoredJson(): JSONObject = toApiJson().put("channel", channel)

    public companion object {
        public const val CORE_CHANNEL: String = "core"

        internal fun fromStoredJson(json: JSONObject): BrudcastEvent? {
            val id = json.optString("id").ifEmpty { return null }
            val type = json.optString("type").ifEmpty { return null }
            val occurredAt = BrudcastDates.parse(json.optString("occurredAt")) ?: return null
            fun text(key: String): String? = if (json.isNull(key)) null else json.optString(key).ifEmpty { null }
            return BrudcastEvent(
                type = type,
                messageId = text("messageId"),
                buttonId = text("buttonId"),
                name = text("name"),
                properties = BrudcastValue.propertiesFromJson(json.optJSONObject("properties")),
                occurredAt = occurredAt,
                channel = text("channel") ?: CORE_CHANNEL,
                id = id,
            )
        }
    }
}

/**
 * ISO 8601 dates with milliseconds, as the service writes and reads them.
 */
public object BrudcastDates {
    private val withFraction = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
    private val withoutFraction = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    /**
     * Formats epoch milliseconds as ISO 8601 in UTC.
     *
     * @param millis The time.
     * @return The formatted string, ending in `Z`.
     */
    @JvmStatic
    public fun format(millis: Long): String = withFraction.get()!!.format(Date(millis)).replace("+00:00", "Z")

    /**
     * Parses an ISO 8601 date with or without milliseconds.
     *
     * @param text The string.
     * @return Epoch milliseconds, or null when the string is not a date.
     */
    @JvmStatic
    public fun parse(text: String?): Long? {
        if (text.isNullOrEmpty()) return null
        return try {
            withFraction.get()!!.parse(text)?.time
        } catch (_: ParseException) {
            try {
                withoutFraction.get()!!.parse(text)?.time
            } catch (_: ParseException) {
                null
            }
        }
    }
}
