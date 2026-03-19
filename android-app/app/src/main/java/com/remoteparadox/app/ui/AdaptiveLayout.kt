package com.remoteparadox.app.ui

private const val MIN_PARTITION_WIDTH_DP = 300f

/**
 * Determines how many partition columns to display side-by-side based on
 * available width. On phones only 1 is visible; on foldables 2; on tablets 3+.
 * The result is capped by [totalPartitions] so we never allocate empty columns.
 */
fun calculateVisiblePartitions(availableWidthDp: Float, totalPartitions: Int): Int {
    val maxVisible = (availableWidthDp / MIN_PARTITION_WIDTH_DP).toInt().coerceAtLeast(1)
    return maxVisible.coerceAtMost(totalPartitions).coerceAtLeast(1)
}
