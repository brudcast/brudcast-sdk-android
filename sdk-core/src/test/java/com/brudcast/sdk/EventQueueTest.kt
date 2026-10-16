package com.brudcast.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EventQueueTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var clock = 1_800_000_000_000L

    private fun queue(storage: BrudcastStorage? = null) = EventQueue(storage) { clock }

    private fun event(channel: String = BrudcastEvent.CORE_CHANNEL, at: Long = clock) =
        BrudcastEvent(type = "custom", name = "tapped", occurredAt = at, channel = channel)

    @Test
    fun signalsAFlushOnceTwentyEventsWait() {
        val queue = queue()
        repeat(19) { assertFalse(queue.enqueue(event())) }
        assertTrue(queue.enqueue(event()))
    }

    @Test
    fun batchesHoldAtMostOneHundredEventsOfOneChannel() {
        val queue = queue()
        queue.enqueue(event(channel = "push"))
        repeat(150) { queue.enqueue(event()) }
        val first = queue.nextBatch()!!
        assertEquals(1, first.size)
        assertEquals("push", first[0].channel)
        queue.complete(first, EventQueue.Outcome.SENT)
        val second = queue.nextBatch()!!
        assertEquals(100, second.size)
        assertTrue(second.all { it.channel == BrudcastEvent.CORE_CHANNEL })
    }

    @Test
    fun onlyOneBatchIsOutAtATime() {
        val queue = queue()
        queue.enqueue(event())
        assertNotNull(queue.nextBatch())
        assertNull(queue.nextBatch())
    }

    @Test
    fun retryBacksOffExponentiallyUpToFiveMinutes() {
        val queue = queue()
        queue.enqueue(event())
        queue.complete(queue.nextBatch()!!, EventQueue.Outcome.RETRY)
        assertEquals(clock + 2_000, queue.retryAfter)
        assertNull(queue.nextBatch())
        clock += 2_000
        queue.complete(queue.nextBatch()!!, EventQueue.Outcome.RETRY)
        assertEquals(clock + 4_000, queue.retryAfter)
        repeat(10) {
            clock = queue.retryAfter!!
            queue.complete(queue.nextBatch()!!, EventQueue.Outcome.RETRY)
        }
        assertEquals(clock + 300_000, queue.retryAfter)
        assertEquals(1, queue.waitingCount)
    }

    @Test
    fun rejectedBatchesAreDroppedAndUnauthorizedOnesKept() {
        val queue = queue()
        queue.enqueue(event())
        queue.complete(queue.nextBatch()!!, EventQueue.Outcome.UNAUTHORIZED)
        assertEquals(1, queue.waitingCount)
        queue.complete(queue.nextBatch()!!, EventQueue.Outcome.REJECTED)
        assertEquals(0, queue.waitingCount)
    }

    @Test
    fun eventsOlderThanSevenDaysAreDropped() {
        val queue = queue()
        queue.enqueue(event(at = clock - 8L * 24 * 3_600 * 1_000))
        queue.enqueue(event())
        assertEquals(1, queue.nextBatch()!!.size)
    }

    @Test
    fun theQueueSurvivesARestart() {
        val storage = BrudcastStorage(null, folder.newFolder())
        val original = BrudcastEvent(
            type = "click", messageId = "m1", buttonId = "cta",
            properties = mapOf("plan" to BrudcastValue.Text("pro"), "seats" to BrudcastValue.Number(3.0)),
            occurredAt = clock, channel = "in_app",
        )
        queue(storage).enqueue(original)
        assertEquals(listOf(original), queue(storage).events)
    }

    @Test
    fun theApiJsonOmitsTheChannelAndWritesUtc() {
        val json = event(channel = "push").toApiJson()
        assertFalse(json.has("channel"))
        assertEquals("2027-01-15T08:00:00.000Z", json.getString("occurredAt"))
    }

    @Test
    fun wholeNumbersAreWrittenAsIntegers() {
        val json = BrudcastValue.propertiesToJson(mapOf("n" to BrudcastValue.Number(3.0), "d" to BrudcastValue.Number(1.5)))
        assertEquals(3L, json.get("n"))
        assertEquals(1.5, json.get("d"))
    }
}
