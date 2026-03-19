package com.remoteparadox.app

import com.remoteparadox.app.ui.calculateVisiblePartitions
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveLayoutTest {

    @Test
    fun `phone width shows single partition`() {
        assertEquals(1, calculateVisiblePartitions(360f, 3))
    }

    @Test
    fun `foldable unfolded shows two partitions`() {
        assertEquals(2, calculateVisiblePartitions(600f, 3))
    }

    @Test
    fun `foldable with only one partition still shows one`() {
        assertEquals(1, calculateVisiblePartitions(600f, 1))
    }

    @Test
    fun `tablet shows three partitions`() {
        assertEquals(3, calculateVisiblePartitions(1000f, 4))
    }

    @Test
    fun `tablet capped by total partition count`() {
        assertEquals(2, calculateVisiblePartitions(1000f, 2))
    }

    @Test
    fun `very narrow width still shows at least one`() {
        assertEquals(1, calculateVisiblePartitions(200f, 5))
    }

    @Test
    fun `zero partitions returns one as minimum`() {
        assertEquals(1, calculateVisiblePartitions(600f, 0))
    }

    @Test
    fun `width just below two-partition threshold shows one`() {
        assertEquals(1, calculateVisiblePartitions(599f, 2))
    }

    @Test
    fun `width exactly at two-partition threshold shows two`() {
        assertEquals(2, calculateVisiblePartitions(600f, 2))
    }

    @Test
    fun `large tablet landscape shows up to four partitions`() {
        assertEquals(4, calculateVisiblePartitions(1280f, 5))
    }
}
