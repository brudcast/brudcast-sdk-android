package com.brudcast.sdk.inapp

import com.brudcast.sdk.BrudcastDates
import com.brudcast.sdk.BrudcastStorage
import org.json.JSONArray
import org.json.JSONObject

internal data class FrequencyCap(val count: Int, val hours: Double, val impressionsInWindow: Int) {
    fun toJson(): JSONObject = JSONObject().put("count", count).put("hours", hours).put("impressionsInWindow", impressionsInWindow)

    companion object {
        fun parse(json: JSONObject?): FrequencyCap? {
            json ?: return null
            val count = json.optInt("count", 0)
            val hours = json.number("hours") ?: 0.0
            if (count <= 0 || hours <= 0) return null
            return FrequencyCap(count, hours, json.optInt("impressionsInWindow", 0))
        }
    }
}

/**
 * The messages this device holds, and the local record of what it has shown.
 *
 * The service's counts are authoritative but lag behind events still waiting in the queue, so every
 * limit takes the larger of the service's figure and the local one.
 */
internal class MessageStore(
    private val storage: BrudcastStorage?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Change(val added: List<InAppMessage>, val removed: List<String>)

    var messages: List<InAppMessage> = emptyList()
        private set
    var frequencyCap: FrequencyCap? = null
        private set
    var userId: String? = null
        private set

    private var rawMessages = JSONArray()
    private val localImpressions = HashMap<String, Int>()
    private val ledger = ArrayList<Long>()
    private var impressionsSinceSync = 0
    private val delivered = LinkedHashSet<String>()
    private val reportedUnsupported = HashSet<String>()

    init {
        load()
    }

    /**
     * Replaces the held messages with a feed. Anything absent was withdrawn.
     *
     * @param raw The feed's `messages` array.
     * @param cap The feed's `frequencyCap`.
     * @param user The session's user, so a different user's messages are never kept.
     * @return Which messages are new to this device, and which ids went away.
     */
    fun replace(raw: JSONArray, cap: JSONObject?, user: String): Change {
        if (userId != user) {
            clearUserState()
            userId = user
        }
        val parsed = (0 until raw.length()).mapNotNull { InAppMessage.parse(raw.optJSONObject(it)) }
        val oldIds = messages.map { it.id }.toSet()
        val newIds = parsed.map { it.id }.toSet()
        val change = Change(parsed.filter { it.id !in oldIds }, messages.map { it.id }.filter { it !in newIds })
        messages = parsed
        rawMessages = raw
        frequencyCap = FrequencyCap.parse(cap)
        impressionsSinceSync = 0
        localImpressions.keys.retainAll(newIds)
        persist()
        return change
    }

    fun message(id: String): InAppMessage? = messages.firstOrNull { it.id == id }

    /**
     * Marks messages as delivered.
     *
     * @param ids Message ids just stored.
     * @return The ids not delivered before, which need a `delivered` event.
     */
    fun markDelivered(ids: List<String>): List<String> {
        val fresh = ids.filter { it !in delivered }
        if (fresh.isEmpty()) return fresh
        delivered.addAll(fresh)
        if (delivered.size > 2_000) {
            val held = messages.map { it.id }.toSet()
            delivered.retainAll(held)
        }
        persist()
        return fresh
    }

    /**
     * Marks an unsupported design as reported.
     *
     * @param id The message id.
     * @return True the first time, when `render_unsupported` should be sent.
     */
    fun markUnsupportedReported(id: String): Boolean {
        if (!reportedUnsupported.add(id)) return false
        persist()
        return true
    }

    fun recordImpression(id: String) {
        val current = now()
        localImpressions[id] = impressionCount(id) + 1
        ledger.add(current)
        impressionsSinceSync += 1
        ledger.removeAll { current - it > LEDGER_WINDOW_MS }
        persist()
    }

    fun markRead(id: String) {
        messages = messages.map { if (it.id == id) it.copy(read = true) else it }
    }

    fun impressionCount(id: String): Int = maxOf(message(id)?.impressionCount ?: 0, localImpressions[id] ?: 0)

    /**
     * Decides whether a message may be shown now.
     *
     * @param message The message.
     * @param serverNow The current time on the service's clock.
     * @return True when it is a displayable design inside its window and under every limit.
     */
    fun isEligible(message: InAppMessage, serverNow: Long): Boolean {
        if (!message.display || message.format == InAppFormat.INBOX_ONLY || message.design == null) return false
        message.startsAt?.let { if (it > serverNow) return false }
        message.endsAt?.let { if (it < serverNow) return false }
        if (message.test) return true
        if (impressionCount(message.id) >= message.displayRules.maxImpressions) return false
        frequencyCap?.let { cap ->
            val windowStart = now() - (cap.hours * 3_600_000).toLong()
            val local = ledger.count { it >= windowStart }
            val inWindow = maxOf(cap.impressionsInWindow + impressionsSinceSync, local)
            if (inWindow >= cap.count) return false
        }
        return true
    }

    fun clearUserState() {
        messages = emptyList()
        rawMessages = JSONArray()
        frequencyCap = null
        localImpressions.clear()
        impressionsSinceSync = 0
        delivered.clear()
        reportedUnsupported.clear()
        persist()
    }

    private fun load() {
        val storage = storage ?: return
        storage.readObject(FEED_FILE)?.let { feed ->
            userId = feed.text("userId")
            rawMessages = feed.optJSONArray("messages") ?: JSONArray()
            messages = (0 until rawMessages.length()).mapNotNull { InAppMessage.parse(rawMessages.optJSONObject(it)) }
            frequencyCap = FrequencyCap.parse(feed.optJSONObject("frequencyCap"))
        }
        storage.readObject(STATE_FILE)?.let { state ->
            state.optJSONObject("impressions")?.let { counts ->
                counts.keys().forEach { key -> localImpressions[key] = counts.optInt(key) }
            }
            state.optJSONArray("ledger")?.let { array ->
                (0 until array.length()).mapNotNull { BrudcastDates.parse(array.optString(it)) }.let(ledger::addAll)
            }
            impressionsSinceSync = state.optInt("sinceSync", 0)
            state.optJSONArray("delivered")?.let { array -> (0 until array.length()).forEach { delivered.add(array.optString(it)) } }
            state.optJSONArray("unsupported")?.let { array -> (0 until array.length()).forEach { reportedUnsupported.add(array.optString(it)) } }
        }
    }

    private fun persist() {
        val storage = storage ?: return
        val feed = JSONObject().put("messages", rawMessages)
        userId?.let { feed.put("userId", it) }
        frequencyCap?.let { feed.put("frequencyCap", it.toJson()) }
        storage.write(FEED_FILE, feed)
        val counts = JSONObject()
        localImpressions.forEach { (key, value) -> counts.put(key, value) }
        storage.write(
            STATE_FILE,
            JSONObject()
                .put("impressions", counts)
                .put("ledger", JSONArray(ledger.map(BrudcastDates::format)))
                .put("sinceSync", impressionsSinceSync)
                .put("delivered", JSONArray(delivered.toList()))
                .put("unsupported", JSONArray(reportedUnsupported.toList())),
        )
    }

    private companion object {
        const val FEED_FILE = "in_app_feed.json"
        const val STATE_FILE = "in_app_state.json"
        const val LEDGER_WINDOW_MS = 168L * 3_600 * 1_000
    }
}
