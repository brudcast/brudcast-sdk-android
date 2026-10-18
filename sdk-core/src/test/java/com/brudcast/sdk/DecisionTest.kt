package com.brudcast.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionTest {
    @Test
    fun keepsTheFirstAnswer() {
        val answers = ArrayList<BrudcastActionResult>()
        val decision = BrudcastDecision(BrudcastActionResult.USE_DEFAULT, timeoutMessage = "late") { answers.add(it) }
        decision.decide(BrudcastActionResult.HANDLED)
        decision.decide(BrudcastActionResult.USE_DEFAULT)
        assertEquals(listOf(BrudcastActionResult.HANDLED), answers)
    }

    @Test
    fun usesTheFallbackWhenTheHandlerFails() {
        val answers = ArrayList<BrudcastActionResult>()
        val decision = BrudcastDecision(BrudcastActionResult.USE_DEFAULT, timeoutMessage = "late") { answers.add(it) }
        decision.fail(IllegalStateException("boom"), "action handler")
        decision.decide(BrudcastActionResult.HANDLED)
        assertEquals(listOf(BrudcastActionResult.USE_DEFAULT), answers)
    }
}
