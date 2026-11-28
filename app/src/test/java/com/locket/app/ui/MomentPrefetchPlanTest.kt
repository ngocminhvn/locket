package com.locket.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentPrefetchPlanTest {
    @Test
    fun eightAheadAndThreeBehind() {
        val plan = momentPrefetchPlan(30, 10, 10, 8, true)
        assertEquals(listOf(10), plan.urgent)
        assertEquals((11..18).toList() + listOf(9, 8, 7), plan.nearby)
    }

    @Test
    fun reverseDirectionAndTargetArePrioritizedWithoutDuplicates() {
        val plan = momentPrefetchPlan(30, 10, 8, 8, false)
        assertEquals(listOf(10, 8), plan.urgent)
        assertTrue(plan.nearby.containsAll(listOf(9, 7, 6, 5, 4, 3, 2, 11, 12, 13)))
        assertEquals(plan.nearby.distinct(), plan.nearby)
        assertTrue(plan.urgent.none { it in plan.nearby })
    }

    @Test
    fun cameraAndEmptyFeedStayInBounds() {
        assertEquals((0..7).toList(), momentPrefetchPlan(20, -1, -1, 8, true).nearby)
        assertEquals(MomentPrefetchPlan(emptyList(), emptyList()), momentPrefetchPlan(0, -1, -1, 8, true))
    }
}
