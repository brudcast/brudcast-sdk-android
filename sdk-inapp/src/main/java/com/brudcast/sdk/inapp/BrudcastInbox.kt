package com.brudcast.sdk.inapp

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.brudcast.sdk.BrudcastApiError
import com.brudcast.sdk.BrudcastContext
import com.brudcast.sdk.BrudcastDates
import com.brudcast.sdk.BrudcastEvent
import com.brudcast.sdk.BrudcastHttpMethod
import com.brudcast.sdk.BrudcastResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** One page of the inbox, newest first. */
public data class BrudcastInboxPage(
    val messages: List<BrudcastInAppMessage>,
    val unreadCount: Int,
    val hasMore: Boolean,
)

/** Receives the unread count whenever it changes. Called on the main thread. */
public fun interface BrudcastUnreadCountListener {
    public fun onUnreadCountChanged(count: Int)
}

/**
 * The in-app inbox: messages to read later, kept by the service across devices.
 */
public class BrudcastInbox internal constructor() {
    private val main = Handler(Looper.getMainLooper())
    private var module: InAppModule? = null
    private var context: BrudcastContext? = null
    private val entries = ConcurrentHashMap<String, InAppMessage>()
    private val unread = MutableStateFlow(0)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<BrudcastUnreadCountListener>()

    internal fun attach(module: InAppModule, context: BrudcastContext) {
        this.module = module
        this.context = context
    }

    /** The number of unread entries, as last reported by the service. */
    public val unreadCount: StateFlow<Int> = unread.asStateFlow()

    /**
     * Adds a listener for the unread count. It is called at once with the current count.
     *
     * @param listener The listener.
     */
    public fun addUnreadCountListener(listener: BrudcastUnreadCountListener) {
        listeners.add(listener)
        main.post { listener.onUnreadCountChanged(unread.value) }
    }

    /**
     * Removes a listener added with [addUnreadCountListener].
     *
     * @param listener The listener.
     */
    public fun removeUnreadCountListener(listener: BrudcastUnreadCountListener) {
        listeners.remove(listener)
    }

    /**
     * Fetches a page of the inbox.
     *
     * @param before Return entries older than this epoch millisecond time, for the next page.
     * @param limit At most this many entries, up to 100.
     * @param completion Called on the main thread with the page or the error.
     */
    @JvmOverloads
    public fun list(before: Long? = null, limit: Int = 20, completion: (Result<BrudcastInboxPage>) -> Unit) {
        val context = context ?: run {
            main.post { completion(Result.failure(BrudcastApiError(null, "not_initialized", "InAppModule is not installed"))) }
            return
        }
        val query = HashMap<String, String>()
        query["limit"] = limit.coerceIn(1, 100).toString()
        before?.let { query["before"] = BrudcastDates.format(it) }
        context.post {
            context.request(BrudcastHttpMethod.GET, "/inbox", query) { result ->
                val mapped = when (result) {
                    is BrudcastResult.Success -> {
                        val raw = result.data.optJSONArray("messages")
                        val parsed = if (raw == null) emptyList() else (0 until raw.length()).mapNotNull { InAppMessage.parse(raw.optJSONObject(it)) }
                        parsed.forEach { entries[it.id] = it }
                        val count = result.data.optInt("unreadCount", 0)
                        publish(count)
                        Result.success(BrudcastInboxPage(parsed.map { it.toPublic() }, count, result.data.optBoolean("hasMore", false)))
                    }
                    is BrudcastResult.Failure -> Result.failure(result.error)
                }
                main.post { completion(mapped) }
            }
        }
    }

    /**
     * Marks an entry read.
     *
     * @param messageId The entry's message id.
     */
    public fun markRead(messageId: String) {
        val context = context ?: return
        context.post {
            val entry = entries[messageId] ?: return@post
            if (entry.read) return@post
            entries[messageId] = entry.copy(read = true)
            publish(maxOf(0, unread.value - 1))
            module?.inboxMessageOpened(messageId)
            context.enqueue(BrudcastEvent(type = "inbox_read", messageId = messageId, channel = InAppModule.EVENT_CHANNEL))
            context.flush()
        }
    }

    /**
     * Deletes an entry from the inbox on every device.
     *
     * @param messageId The entry's message id.
     */
    public fun delete(messageId: String) {
        val context = context ?: return
        context.post {
            val entry = entries.remove(messageId)
            if (entry?.read == false) publish(maxOf(0, unread.value - 1))
            context.enqueue(BrudcastEvent(type = "inbox_delete", messageId = messageId, channel = InAppModule.EVENT_CHANNEL))
            context.flush()
        }
    }

    /**
     * Opens the default inbox screen.
     *
     * @param context The context to start it from.
     * @param title The screen title.
     */
    @JvmOverloads
    public fun present(context: Context, title: String = "Inbox") {
        val intent = Intent(context, BrudcastInboxActivity::class.java).putExtra(BrudcastInboxActivity.EXTRA_TITLE, title)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    internal fun refreshUnreadCount() {
        context?.request(BrudcastHttpMethod.GET, "/inbox", mapOf("limit" to "1")) { result ->
            if (result is BrudcastResult.Success && result.data.has("unreadCount")) publish(result.data.optInt("unreadCount"))
        }
    }

    internal fun clear() {
        entries.clear()
        publish(0)
    }

    internal fun entry(id: String): InAppMessage? = entries[id]

    internal fun buttonTapped(messageId: String, buttonId: String?, action: InAppAction) {
        context?.post { module?.handleButton(messageId, buttonId, action) }
    }

    private fun publish(count: Int) {
        if (unread.value == count) return
        unread.value = count
        main.post { listeners.forEach { it.onUnreadCountChanged(count) } }
    }
}
