package com.brudcast.sdk.inapp

import com.brudcast.sdk.BrudcastDates
import com.brudcast.sdk.BrudcastValue
import org.json.JSONObject

internal enum class InAppTriggerType(val wire: String) {
    ON_SESSION("on_session"), EVENT("event"), SCREEN("screen");

    companion object {
        fun from(value: String?): InAppTriggerType? = entries.firstOrNull { it.wire == value }
    }
}

internal enum class InAppFilterOperator(val wire: String) {
    EQ("eq"), NEQ("neq"), GT("gt"), LT("lt"), EXISTS("exists");

    companion object {
        fun from(value: String?): InAppFilterOperator? = entries.firstOrNull { it.wire == value }
    }
}

internal data class InAppFilter(val property: String, val operator: InAppFilterOperator, val value: BrudcastValue)

internal data class InAppTrigger(
    val type: InAppTriggerType,
    val eventName: String?,
    val screenName: String?,
    val filters: List<InAppFilter>,
) {
    companion object {
        val ON_SESSION = InAppTrigger(InAppTriggerType.ON_SESSION, null, null, emptyList())

        fun parse(json: JSONObject?): InAppTrigger? {
            json ?: return ON_SESSION
            val type = InAppTriggerType.from(json.text("type") ?: "on_session") ?: return null
            val filters = ArrayList<InAppFilter>()
            val raw = json.optJSONArray("filters")
            if (raw != null) {
                for (index in 0 until raw.length()) {
                    val filter = raw.optJSONObject(index) ?: return null
                    val property = filter.text("property") ?: return null
                    val operator = InAppFilterOperator.from(filter.text("operator")) ?: return null
                    filters.add(InAppFilter(property, operator, BrudcastValue.from(filter.opt("value")) ?: BrudcastValue.Null))
                }
            }
            return InAppTrigger(type, json.text("eventName"), json.text("screenName"), filters)
        }
    }
}

internal enum class InAppPriority(val wire: String, val rank: Int) {
    LOW("low", 0), NORMAL("normal", 1), HIGH("high", 2);

    companion object {
        fun from(value: String?): InAppPriority = entries.firstOrNull { it.wire == value } ?: NORMAL
    }
}

internal data class InAppDisplayRules(val maxImpressions: Int, val delaySeconds: Double, val priority: InAppPriority) {
    companion object {
        fun parse(json: JSONObject?): InAppDisplayRules {
            val source = json ?: JSONObject()
            return InAppDisplayRules(
                maxImpressions = maxOf(1, source.optInt("maxImpressions", 1)),
                delaySeconds = maxOf(0.0, source.number("delaySeconds") ?: 0.0),
                priority = InAppPriority.from(source.text("priority")),
            )
        }
    }
}

internal sealed class InAppDesignState {
    data class Ready(val design: InAppDesign) : InAppDesignState()
    data class Unsupported(val error: InAppDesignError) : InAppDesignState()
}

/** One message the service told this device to hold. */
internal data class InAppMessage(
    val id: String,
    val kind: String,
    val campaignId: String?,
    val test: Boolean,
    val format: InAppFormat,
    val designState: InAppDesignState,
    val rawDesign: JSONObject,
    val trigger: InAppTrigger,
    val displayRules: InAppDisplayRules,
    val inboxTitle: String,
    val inboxPreview: String,
    val inboxThumbnailUrl: String?,
    val display: Boolean,
    val inboxEntry: Boolean,
    val read: Boolean,
    val impressionCount: Int,
    val startsAt: Long?,
    val endsAt: Long?,
    val createdAt: Long,
) {
    val design: InAppDesign? get() = (designState as? InAppDesignState.Ready)?.design

    fun toPublic(): BrudcastInAppMessage = BrudcastInAppMessage(
        id = id,
        campaignId = campaignId,
        format = format,
        isTest = test,
        title = inboxTitle.ifEmpty { design?.title.orEmpty() },
        preview = inboxPreview.ifEmpty { design?.bodyText.orEmpty() },
        thumbnailUrl = inboxThumbnailUrl ?: design?.firstImageUrl,
        isRead = read,
        createdAt = createdAt,
        design = rawDesign.toString(),
    )

    companion object {
        /**
         * Reads one entry of a feed or inbox page.
         *
         * @param json The message view.
         * @return The message, or null when it lacks an id, a format or a readable trigger. A design
         *   this SDK cannot draw still yields a message, marked unsupported, so it can be reported.
         */
        fun parse(json: JSONObject?): InAppMessage? {
            json ?: return null
            val id = json.text("id") ?: return null
            val format = InAppFormat.from(json.text("format")) ?: return null
            val trigger = InAppTrigger.parse(json.optJSONObject("trigger")) ?: return null
            val rawDesign = json.optJSONObject("design") ?: JSONObject()
            val state = try {
                InAppDesignState.Ready(InAppDesign.parse(rawDesign))
            } catch (error: InAppDesignError) {
                InAppDesignState.Unsupported(error)
            }
            val inbox = json.optJSONObject("inbox") ?: JSONObject()
            return InAppMessage(
                id = id,
                kind = json.text("kind") ?: "campaign",
                campaignId = json.text("campaignId"),
                test = json.optBoolean("test", false),
                format = format,
                designState = state,
                rawDesign = rawDesign,
                trigger = trigger,
                displayRules = InAppDisplayRules.parse(json.optJSONObject("displayRules")),
                inboxTitle = inbox.text("title").orEmpty(),
                inboxPreview = inbox.text("preview").orEmpty(),
                inboxThumbnailUrl = inbox.text("thumbnailUrl"),
                display = if (json.has("display")) json.optBoolean("display") else format != InAppFormat.INBOX_ONLY,
                inboxEntry = json.optBoolean("inboxEntry", false),
                read = json.optBoolean("read", false),
                impressionCount = json.optInt("impressionCount", 0),
                startsAt = BrudcastDates.parse(json.text("startsAt")),
                endsAt = BrudcastDates.parse(json.text("endsAt")),
                createdAt = BrudcastDates.parse(json.text("createdAt")) ?: System.currentTimeMillis(),
            )
        }
    }
}

/**
 * A message as the host app sees it, in the message handler and the inbox.
 *
 * @property design The design document as JSON, for apps that draw messages themselves.
 */
public data class BrudcastInAppMessage(
    val id: String,
    val campaignId: String?,
    val format: InAppFormat,
    val isTest: Boolean,
    val title: String,
    val preview: String,
    val thumbnailUrl: String?,
    val isRead: Boolean,
    val createdAt: Long,
    val design: String,
)
