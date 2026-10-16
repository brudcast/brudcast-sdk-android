package com.brudcast.sdk.inapp

import android.os.Handler
import android.os.Looper
import com.brudcast.sdk.Brudcast
import com.brudcast.sdk.BrudcastAction
import com.brudcast.sdk.BrudcastContext
import com.brudcast.sdk.BrudcastEvent
import com.brudcast.sdk.BrudcastHttpMethod
import com.brudcast.sdk.BrudcastLogger
import com.brudcast.sdk.BrudcastModule
import com.brudcast.sdk.BrudcastPushPermissionRequesting
import com.brudcast.sdk.BrudcastResult
import com.brudcast.sdk.BrudcastSdk
import com.brudcast.sdk.BrudcastSession
import com.brudcast.sdk.BrudcastValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What the host's message handler wants done with a message about to show. */
public enum class BrudcastMessageDecision {
    /** Show it with the standard renderer. */
    SHOW,

    /** Do not show it for this trigger. */
    SUPPRESS,

    /** Do not show it now; the next trigger may show it. */
    DEFER,

    /** The host draws it, and reports impression, click and dismiss itself. */
    CUSTOM,
}

/** Decides what happens to a message about to show. Called on the main thread. */
public fun interface BrudcastMessageHandler {
    public fun handle(message: BrudcastInAppMessage): BrudcastMessageDecision
}

/** Why a message closed without an action. */
public enum class BrudcastDismissReason(public val wire: String) {
    CLOSE_BUTTON("close_button"), OVERLAY_TAP("overlay_tap"), SWIPE("swipe"), BACK("back"), AUTO("auto"), HOST("host")
}

/** In-app messaging. Add [InAppModule] to `BrudcastOptions.modules` to run it. */
public val Brudcast.inApp: InAppModule
    get() = InAppModule

/**
 * In-app messaging: sync, triggers, display rules, presentation and the inbox.
 */
public object InAppModule : BrudcastModule {
    override val name: String = "in_app"

    internal const val EVENT_CHANNEL = "in_app"
    private const val IMAGE_TIMEOUT_MS = 3_000L

    /** The inbox. */
    public val inbox: BrudcastInbox = BrudcastInbox()

    private data class Queued(val id: String, val sequence: Int, val occurrence: InAppOccurrence)

    private data class Delayed(val id: String, val future: ScheduledFuture<*>, val screen: String?)

    private val main = Handler(Looper.getMainLooper())
    internal var context: BrudcastContext? = null
        private set
    private var store: MessageStore? = null
    private var stream: StreamClient? = null
    private val considered = HashSet<String>()
    private var lastUserId: String? = null
    private val queued = ArrayList<Queued>()
    private var sequence = 0
    private var presentingId: String? = null
    private var delayed: Delayed? = null
    private var paused = false
    private var currentScreen: String? = null
    private var syncScheduled = false

    @Volatile
    private var messageHandler: BrudcastMessageHandler? = null

    override fun attach(context: BrudcastContext) {
        this.context = context
        store = MessageStore(context.storage)
        inbox.attach(this, context)
        stream = StreamClient(
            context.executor,
            onEvent = { scheduleSync() },
            onUnauthorized = { sync() },
        )
        InAppPresenter.install(context.application)
        InAppImageLoader.install(context.application.cacheDir)
    }

    override fun sessionDidOpen(session: BrudcastSession, payload: JSONObject) {
        if (lastUserId != session.userId) {
            considered.clear()
            queued.clear()
            lastUserId = session.userId
        }
        apply(payload, session)
        inbox.refreshUnreadCount()
        if (context?.isAppActive == true) {
            startStream()
            stream?.restart()
        }
    }

    override fun didReset() {
        store?.clearUserState()
        considered.clear()
        queued.clear()
        cancelDelayed()
        inbox.clear()
        stream?.stop()
    }

    override fun appDidBecomeActive() {
        sync()
        startStream()
        pump()
    }

    override fun appDidEnterBackground() {
        stream?.stop()
        cancelDelayed()
    }

    override fun didTrack(name: String, properties: Map<String, BrudcastValue>) {
        evaluate(InAppOccurrence.Event(name, properties))
    }

    override fun didScreen(name: String, properties: Map<String, BrudcastValue>) {
        val pending = delayed
        if (pending?.screen != null && pending.screen != name) cancelDelayed()
        currentScreen = name
        evaluate(InAppOccurrence.Screen(name, properties))
    }

    /** Holds messages back without showing them, such as during checkout or a video. */
    public fun pauseDisplay() {
        post { paused = true }
    }

    /** Shows held messages again after [pauseDisplay]. */
    public fun resumeDisplay() {
        post {
            paused = false
            pump()
        }
    }

    /**
     * Sets the handler asked before each message shows. Called on the main thread.
     *
     * @param handler The handler, or null to show every message.
     */
    public fun setMessageHandler(handler: BrudcastMessageHandler?) {
        messageHandler = handler
    }

    /**
     * Reports that the host showed a message it draws itself.
     *
     * @param messageId The message id.
     */
    public fun reportImpression(messageId: String) {
        post { recordImpression(messageId) }
    }

    /**
     * Reports a tap on a button of a message the host draws itself.
     *
     * @param messageId The message id.
     * @param buttonId The design's button id.
     */
    public fun reportClick(messageId: String, buttonId: String) {
        post { context?.enqueue(BrudcastEvent(type = "click", messageId = messageId, buttonId = buttonId, channel = EVENT_CHANNEL)) }
    }

    /**
     * Reports that a message the host draws itself was closed without an action.
     *
     * @param messageId The message id.
     */
    public fun reportDismiss(messageId: String) {
        post { enqueueDismiss(messageId, null, BrudcastDismissReason.HOST) }
    }

    /** Fetches the messages this device should hold now, instead of waiting for the next foreground. */
    public fun sync() {
        val context = context ?: return
        context.post {
            context.request(BrudcastHttpMethod.GET, "/messages") { result ->
                when (result) {
                    is BrudcastResult.Success -> context.session?.let { apply(result.data, it) }
                    is BrudcastResult.Failure -> BrudcastLogger.info("Sync failed: ${result.error}")
                }
            }
        }
    }

    private fun post(task: () -> Unit) {
        context?.post(task) ?: task()
    }

    private fun scheduleSync() {
        val context = context ?: return
        if (syncScheduled) return
        syncScheduled = true
        context.executor.schedule({
            context.post {
                syncScheduled = false
                sync()
                inbox.refreshUnreadCount()
            }
        }, 500, TimeUnit.MILLISECONDS)
    }

    private fun startStream() {
        val context = context ?: return
        stream?.start {
            val session = context.session ?: return@start null
            context.api.url("/stream") to session.token
        }
    }

    private fun apply(payload: JSONObject, session: BrudcastSession) {
        val store = store ?: return
        val context = context ?: return
        val change = store.replace(payload.optJSONArray("messages") ?: JSONArray(), payload.optJSONObject("frequencyCap"), session.userId)
        val removed = change.removed.toSet()
        queued.removeAll { it.id in removed }

        store.markDelivered(store.messages.map { it.id }).forEach { id ->
            context.enqueue(BrudcastEvent(type = "delivered", messageId = id, channel = EVENT_CHANNEL))
        }
        for (message in store.messages) {
            val state = message.designState
            val reason = when {
                state is InAppDesignState.Unsupported -> state.error.message
                message.triggerUnreadable -> "unreadable trigger"
                else -> null
            }
            if (reason != null && store.markUnsupportedReported(message.id)) {
                BrudcastLogger.warning("Message ${message.id} needs a newer SDK or has an unreadable design: $reason")
                context.enqueue(
                    BrudcastEvent(
                        type = "render_unsupported",
                        messageId = message.id,
                        properties = mapOf(
                            "schemaVersion" to BrudcastValue.Number(message.rawDesign.optInt("schemaVersion", 0).toDouble()),
                            "sdkVersion" to BrudcastValue.Text(BrudcastSdk.VERSION),
                        ),
                        channel = EVENT_CHANNEL,
                    ),
                )
            }
        }
        evaluate(InAppOccurrence.Session)
    }

    private fun evaluate(occurrence: InAppOccurrence) {
        val store = store ?: return
        val session = context?.session ?: return
        val now = session.serverNow()
        for (message in store.messages) {
            if (!TriggerMatcher.matches(message.trigger, occurrence)) continue
            if (occurrence is InAppOccurrence.Session && !considered.add(message.id)) continue
            if (message.id == presentingId || delayed?.id == message.id || queued.any { it.id == message.id }) continue
            if (!store.isEligible(message, now)) continue
            sequence += 1
            queued.add(Queued(message.id, sequence, occurrence))
        }
        pump()
    }

    private fun pump() {
        val store = store ?: return
        val context = context ?: return
        val session = context.session ?: return
        if (paused || !context.isAppActive || presentingId != null || delayed != null) return
        val now = session.serverNow()
        queued.removeAll { entry -> store.message(entry.id)?.let { !store.isEligible(it, now) } ?: true }
        val next = queued.sortedWith(
            compareByDescending<Queued> { store.message(it.id)?.displayRules?.priority?.rank ?: 0 }.thenBy { it.sequence },
        ).firstOrNull() ?: return
        val message = store.message(next.id) ?: return
        queued.removeAll { it.id == next.id }

        val delayMs = (message.displayRules.delaySeconds * 1_000).toLong()
        if (delayMs <= 0) {
            present(message)
            return
        }
        val screen = (next.occurrence as? InAppOccurrence.Screen)?.name
        val future = context.executor.schedule({
            context.post {
                delayed = null
                if (screen != null && currentScreen != screen) return@post pump()
                val current = this.store?.message(message.id)
                if (current == null || !context.isAppActive || paused) return@post pump()
                present(current)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
        delayed = Delayed(message.id, future, screen)
    }

    private fun cancelDelayed() {
        delayed?.future?.cancel(false)
        delayed = null
    }

    private fun present(message: InAppMessage) {
        val design = message.design ?: return pump()
        presentingId = message.id
        val publicMessage = message.toPublic()
        main.post {
            val decision = try {
                messageHandler?.handle(publicMessage) ?: BrudcastMessageDecision.SHOW
            } catch (error: RuntimeException) {
                BrudcastLogger.error("The message handler threw: ${error.message}")
                BrudcastMessageDecision.SHOW
            }
            post {
                when (decision) {
                    BrudcastMessageDecision.SHOW -> render(message, design)
                    else -> {
                        BrudcastLogger.debug("The host chose ${decision.name.lowercase()} for message ${message.id}")
                        finishPresenting()
                    }
                }
            }
        }
    }

    private fun render(message: InAppMessage, design: InAppDesign) {
        val context = context ?: return
        InAppImageLoader.prefetch(design.imageUrls, IMAGE_TIMEOUT_MS) { ready ->
            post {
                if (!ready) {
                    BrudcastLogger.info("Images for message ${message.id} were not ready; it waits for the next trigger")
                    return@post finishPresenting()
                }
                main.post {
                    val shown = InAppPresenter.present(message.id, design, context.options, callbacks(message))
                    if (!shown) post { finishPresenting() }
                }
            }
        }
    }

    private fun callbacks(message: InAppMessage) = InAppPresentationCallbacks(
        onImpression = { post { recordImpression(message.id) } },
        onButton = { buttonId, action -> post { handleButton(message.id, buttonId, action) } },
        onDismiss = { reason -> post { enqueueDismiss(message.id, null, reason) } },
        onClosed = { post { finishPresenting() } },
    )

    private fun recordImpression(messageId: String) {
        store?.recordImpression(messageId)
        context?.enqueue(BrudcastEvent(type = "impression", messageId = messageId, channel = EVENT_CHANNEL))
    }

    private fun enqueueDismiss(messageId: String, buttonId: String?, reason: BrudcastDismissReason) {
        context?.enqueue(
            BrudcastEvent(
                type = "dismiss",
                messageId = messageId,
                buttonId = buttonId,
                properties = mapOf("reason" to BrudcastValue.Text(reason.wire)),
                channel = EVENT_CHANNEL,
            ),
        )
    }

    internal fun handleButton(messageId: String, buttonId: String?, action: InAppAction) {
        val context = context ?: return
        if (action.type == InAppActionType.DISMISS) {
            enqueueDismiss(messageId, buttonId, BrudcastDismissReason.CLOSE_BUTTON)
            return
        }
        context.enqueue(BrudcastEvent(type = "click", messageId = messageId, buttonId = buttonId, channel = EVENT_CHANNEL))
        val source = BrudcastAction.Source.InApp(messageId, buttonId)
        when (action.type) {
            InAppActionType.DEEPLINK -> context.perform(BrudcastAction(BrudcastAction.Kind.DEEPLINK, action.value.orEmpty(), source = source))
            InAppActionType.URL -> context.perform(BrudcastAction(BrudcastAction.Kind.URL, action.value.orEmpty(), action.opensInApp, source))
            InAppActionType.CUSTOM_EVENT -> {
                val name = action.value.orEmpty()
                if (Brudcast.isValidEventName(name)) {
                    context.enqueue(BrudcastEvent(type = "custom", name = name, properties = mapOf("messageId" to BrudcastValue.Text(messageId))))
                }
                context.perform(BrudcastAction(BrudcastAction.Kind.CUSTOM_EVENT, name, source = source))
            }
            InAppActionType.REQUEST_PUSH_PERMISSION -> {
                val push = context.module<BrudcastPushPermissionRequesting>()
                if (push == null) {
                    BrudcastLogger.warning("A message asked for push permission, but PushModule is not installed")
                } else {
                    main.post {
                        val activity = context.currentActivity
                        if (activity != null) push.requestPushPermission(activity) {} else BrudcastLogger.warning("No activity to ask for push permission from")
                    }
                }
            }
            InAppActionType.DISMISS -> Unit
        }
    }

    private fun finishPresenting() {
        presentingId = null
        pump()
    }

    internal fun inboxMessageOpened(id: String) {
        store?.markRead(id)
    }
}

/** What a presented message reports back. Every callback is safe to call from the main thread. */
internal data class InAppPresentationCallbacks(
    val onImpression: () -> Unit,
    val onButton: (String?, InAppAction) -> Unit,
    val onDismiss: (BrudcastDismissReason) -> Unit,
    val onClosed: () -> Unit,
)
