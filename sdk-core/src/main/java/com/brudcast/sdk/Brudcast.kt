package com.brudcast.sdk

import android.content.Context

/**
 * The Brudcast SDK.
 *
 * Call [initialize] once, in `Application.onCreate`. Every other call may come before it; calls
 * made earlier are held and run once the SDK starts. No call throws or blocks the main thread.
 */
public object Brudcast {
    private val lock = Any()
    private var core: BrudcastCore? = null
    private val pending = ArrayList<(BrudcastCore) -> Unit>()
    private var pendingActionHandler: BrudcastActionHandler? = null

    /**
     * Starts the SDK.
     *
     * @param context Any context; the application context is kept.
     * @param publishableKey The application's publishable key (`bpk_...`) from the dashboard. It is
     *   public by design; never ship the identity secret in the app.
     * @param options Environment, logging, appearance and the modules to run.
     */
    @JvmStatic
    @JvmOverloads
    public fun initialize(context: Context, publishableKey: String, options: BrudcastOptions = BrudcastOptions()) {
        BrudcastLogger.level = options.logLevel
        val key = publishableKey.trim()
        if (key.isEmpty()) {
            BrudcastLogger.error("initialize needs a publishable key")
            return
        }
        val created: BrudcastCore
        val replay: List<(BrudcastCore) -> Unit>
        synchronized(lock) {
            if (core != null) {
                BrudcastLogger.warning("initialize was called more than once; the first call wins")
                return
            }
            val application = context.applicationContext as android.app.Application
            created = BrudcastCore(application, key, options, BrudcastStorage.standard(application))
            created.actionHandler = pendingActionHandler
            core = created
            replay = pending.toList()
            pending.clear()
        }
        created.start()
        replay.forEach { it(created) }
    }

    /** True once [initialize] has run. */
    @JvmStatic
    public val isInitialized: Boolean
        get() = synchronized(lock) { core != null }

    /** This install's device id, a random UUID kept in no-backup storage. Null before [initialize]. */
    @JvmStatic
    public val deviceId: String?
        get() = synchronized(lock) { core?.deviceId }

    /**
     * Identifies the signed-in user.
     *
     * @param externalId Your id for the user, at most 191 characters.
     * @param userHash `HMAC-SHA256(identitySecret, externalId)` in hex, computed on your server.
     *   Required when the application enforces identity verification.
     * @param traits Details used when Brudcast creates the contact.
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(externalId: String, userHash: String?, traits: BrudcastTraits? = null) {
        dispatch { it.identify(externalId, userHash, traits) }
    }

    /**
     * Forgets the user on logout. The device keeps its id, and its push token stops reaching the
     * previous user.
     */
    @JvmStatic
    public fun reset() {
        dispatch { it.reset() }
    }

    /**
     * Records a custom event. In-app messages triggered by the event show at once.
     *
     * @param name The event name: a letter, then up to 63 letters, digits and `_ . : -`.
     * @param properties Strings, numbers, booleans or nulls.
     */
    @JvmStatic
    @JvmOverloads
    public fun track(name: String, properties: Map<String, Any?> = emptyMap()) {
        val converted = BrudcastValue.properties(properties)
        dispatch { it.track(name, converted) }
    }

    /**
     * Records that the user is on a screen. In-app messages triggered by the screen show at once.
     *
     * @param name The screen name.
     * @param properties Strings, numbers, booleans or nulls.
     */
    @JvmStatic
    @JvmOverloads
    public fun screen(name: String, properties: Map<String, Any?> = emptyMap()) {
        val converted = BrudcastValue.properties(properties)
        dispatch { it.screen(name, converted) }
    }

    /** Stops in-app campaign messages for this user. Transactional messages still arrive. */
    @JvmStatic
    public fun optOut() {
        dispatch { it.setOptedOut(true) }
    }

    /** Resumes in-app campaign messages for this user. */
    @JvmStatic
    public fun optIn() {
        dispatch { it.setOptedOut(false) }
    }

    /** Stops collecting and sending events, for consent frameworks. Waiting events are discarded. */
    @JvmStatic
    public fun disableTracking() {
        dispatch { it.setTrackingEnabled(false) }
    }

    /** Resumes collecting events after [disableTracking]. */
    @JvmStatic
    public fun enableTracking() {
        dispatch { it.setTrackingEnabled(true) }
    }

    /** Sends waiting events now instead of on the next timer tick. */
    @JvmStatic
    public fun flush() {
        dispatch { core -> core.post { core.flush() } }
    }

    /**
     * Sets the handler for deep links, URLs and custom events from in-app buttons and push taps.
     * Return [BrudcastActionResult.HANDLED] to skip the default behaviour. Called on the main thread.
     *
     * @param handler The handler, or null to restore the defaults.
     */
    @JvmStatic
    public fun setActionHandler(handler: BrudcastActionHandler?) {
        synchronized(lock) {
            val current = core
            if (current != null) current.actionHandler = handler else pendingActionHandler = handler
        }
    }

    /**
     * Checks an event name against the rule the service applies.
     *
     * @param name The name.
     * @return True for a letter followed by up to 63 letters, digits and `_ . : -`.
     */
    @JvmStatic
    public fun isValidEventName(name: String): Boolean = BrudcastCore.isValidEventName(name)

    /**
     * Runs a block with the module context on the core thread once the SDK is initialised.
     *
     * @param block The block.
     */
    @JvmStatic
    public fun withContext(block: (BrudcastContext) -> Unit) {
        dispatch { core -> core.post { block(core.context) } }
    }

    private fun dispatch(call: (BrudcastCore) -> Unit) {
        val current = synchronized(lock) {
            val existing = core
            if (existing == null) pending.add(call)
            existing
        }
        current?.let(call)
    }
}
