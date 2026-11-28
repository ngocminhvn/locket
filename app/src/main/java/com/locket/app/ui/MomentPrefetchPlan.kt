package com.locket.app.ui

internal data class MomentPrefetchPlan(val urgent: List<Int>, val nearby: List<Int>)

/** Current and target first; 8 in the scrolling direction, 3 opposite. */
internal fun momentPrefetchPlan(size: Int, current: Int, target: Int, count: Int, forward: Boolean): MomentPrefetchPlan {
    val step = if (forward) 1 else -1
    val urgent = listOf(current, target).filter { it in 0 until size }.distinct()
    val next = (1..count.coerceAtLeast(0)).map { current + step * it }
    val opposite = (1..3).map { current - step * it }
    val targetAhead = if (target == current) emptyList() else (1..5).map { target + step * it }
    val nearby = (next + opposite + targetAhead).filter { it in 0 until size && it !in urgent }.distinct()
    return MomentPrefetchPlan(urgent, nearby)
}
