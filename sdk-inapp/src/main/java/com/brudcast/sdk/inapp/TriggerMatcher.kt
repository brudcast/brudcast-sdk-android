package com.brudcast.sdk.inapp

import com.brudcast.sdk.BrudcastValue

/** What happened in the app that may show a message. */
internal sealed class InAppOccurrence {
    object Session : InAppOccurrence()
    data class Event(val name: String, val properties: Map<String, BrudcastValue>) : InAppOccurrence()
    data class Screen(val name: String, val properties: Map<String, BrudcastValue>) : InAppOccurrence()
}

internal object TriggerMatcher {
    /**
     * Decides whether an occurrence fires a trigger.
     *
     * @param trigger The message's trigger.
     * @param occurrence What happened.
     * @return True when the trigger's type and name match and every filter holds.
     */
    fun matches(trigger: InAppTrigger, occurrence: InAppOccurrence): Boolean = when {
        trigger.type == InAppTriggerType.ON_SESSION && occurrence is InAppOccurrence.Session -> true
        trigger.type == InAppTriggerType.EVENT && occurrence is InAppOccurrence.Event ->
            trigger.eventName == occurrence.name && filtersHold(trigger.filters, occurrence.properties)
        trigger.type == InAppTriggerType.SCREEN && occurrence is InAppOccurrence.Screen ->
            trigger.screenName == occurrence.name && filtersHold(trigger.filters, occurrence.properties)
        else -> false
    }

    fun filtersHold(filters: List<InAppFilter>, properties: Map<String, BrudcastValue>): Boolean =
        filters.all { holds(it, properties[it.property]) }

    fun holds(filter: InAppFilter, actual: BrudcastValue?): Boolean = when (filter.operator) {
        InAppFilterOperator.EXISTS -> actual != null && actual != BrudcastValue.Null
        InAppFilterOperator.EQ -> actual != null && equal(actual, filter.value)
        InAppFilterOperator.NEQ -> actual == null || !equal(actual, filter.value)
        InAppFilterOperator.GT -> compare(actual, filter.value) { left, right -> left > right }
        InAppFilterOperator.LT -> compare(actual, filter.value) { left, right -> left < right }
    }

    private fun compare(actual: BrudcastValue?, expected: BrudcastValue, test: (Double, Double) -> Boolean): Boolean {
        val left = actual?.numberValue() ?: return false
        val right = expected.numberValue() ?: return false
        return test(left, right)
    }

    private fun equal(left: BrudcastValue, right: BrudcastValue): Boolean {
        if (left is BrudcastValue.Number || right is BrudcastValue.Number) {
            val a = left.numberValue() ?: return false
            val b = right.numberValue() ?: return false
            return a == b
        }
        if (left is BrudcastValue.Bool && right is BrudcastValue.Text) return left.value.toString() == right.value.lowercase()
        if (left is BrudcastValue.Text && right is BrudcastValue.Bool) return right.value.toString() == left.value.lowercase()
        return left == right
    }
}
