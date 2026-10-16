package com.brudcast.sdk

import org.json.JSONArray
import kotlin.math.min
import kotlin.math.pow

/**
 * The persistent queue of events waiting to be sent.
 *
 * The queue does no network I/O and owns no thread: the core drives it from its executor, asks it
 * for the next batch, and reports how sending went.
 */
internal class EventQueue(
    private val storage: BrudcastStorage?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { SENT, REJECTED, DISCARDED, RETRY, UNAUTHORIZED }

    private val pending = ArrayList<BrudcastEvent>()
    private val inFlight = HashSet<String>()
    private var failures = 0
    private var dirty = false
    var retryAfter: Long? = null
        private set
    var batchLimit: Int = MAX_BATCH
        private set

    init {
        storage?.readArray(FILE)?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(BrudcastEvent::fromStoredJson)?.let(pending::add)
            }
        }
        dropExpired()
    }

    val events: List<BrudcastEvent> get() = pending.toList()

    val waitingCount: Int get() = pending.size

    /**
     * Adds an event. It reaches disk on the next [persistIfNeeded], so the core decides how often
     * the file is rewritten.
     *
     * @param event The event.
     * @return True when enough events are waiting that a flush should start now.
     */
    fun enqueue(event: BrudcastEvent): Boolean {
        pending.add(event)
        if (pending.size > MAX_STORED) {
            val overflow = pending.size - MAX_STORED
            BrudcastLogger.warning("Event queue is full, dropping the $overflow oldest events")
            repeat(overflow) { pending.removeAt(0) }
        }
        dirty = true
        return pending.size - inFlight.size >= FLUSH_THRESHOLD
    }

    /**
     * Takes the next batch: the oldest waiting events of one channel and one owner, up to the batch
     * limit.
     *
     * @return The batch, or null when nothing waits, a batch is out, or the queue is backing off.
     */
    fun nextBatch(): List<BrudcastEvent>? {
        if (inFlight.isNotEmpty()) return null
        retryAfter?.let { if (it > now()) return null }
        dropExpired()
        val first = pending.firstOrNull() ?: return null
        val batch = pending.filter { it.channel == first.channel && it.owner == first.owner }.take(batchLimit)
        inFlight.addAll(batch.map { it.id })
        return batch
    }

    /**
     * Records how sending a batch went.
     *
     * @param batch The batch [nextBatch] returned.
     * @param outcome Sent and discarded batches leave the queue. A rejected batch of several events
     *   stays and the next batches are halved until the event the service refuses is sent alone and
     *   dropped. Retried and unauthorized batches stay.
     */
    fun complete(batch: List<BrudcastEvent>, outcome: Outcome) {
        val ids = batch.map { it.id }.toSet()
        inFlight.removeAll(ids)
        when {
            outcome == Outcome.SENT -> {
                failures = 0
                retryAfter = null
                batchLimit = MAX_BATCH
                pending.removeAll { it.id in ids }
            }
            outcome == Outcome.REJECTED && batch.size > 1 -> {
                failures = 0
                retryAfter = null
                batchLimit = maxOf(1, batch.size / 2)
            }
            outcome == Outcome.REJECTED || outcome == Outcome.DISCARDED -> {
                BrudcastLogger.warning("${batch.size} events could not be sent; they are dropped")
                failures = 0
                retryAfter = null
                pending.removeAll { it.id in ids }
            }
            outcome == Outcome.RETRY -> {
                failures += 1
                val delay = min(MAX_BACKOFF_MS.toDouble(), BASE_BACKOFF_MS * 2.0.pow(failures - 1))
                retryAfter = now() + delay.toLong()
            }
            else -> retryAfter = null
        }
        dirty = true
        persistIfNeeded()
    }

    /** Removes every waiting event. */
    fun clear() {
        pending.clear()
        inFlight.clear()
        failures = 0
        retryAfter = null
        batchLimit = MAX_BATCH
        dirty = true
        persistIfNeeded()
    }

    /** Writes the queue to disk when it changed since the last write. */
    fun persistIfNeeded() {
        if (!dirty) return
        dirty = false
        val storage = storage ?: return
        val array = JSONArray()
        pending.forEach { array.put(it.toStoredJson()) }
        storage.write(FILE, array)
    }

    private fun dropExpired() {
        val cutoff = now() - MAX_AGE_MS
        val before = pending.size
        pending.removeAll { it.occurredAt < cutoff && it.id !in inFlight }
        if (pending.size != before) {
            dirty = true
            persistIfNeeded()
        }
    }

    companion object {
        const val MAX_BATCH = 100
        const val FLUSH_THRESHOLD = 20
        const val MAX_STORED = 1_000
        const val MAX_AGE_MS = 7L * 24 * 3_600 * 1_000
        const val BASE_BACKOFF_MS = 2_000.0
        const val MAX_BACKOFF_MS = 300_000L
        private const val FILE = "events.json"
    }
}
