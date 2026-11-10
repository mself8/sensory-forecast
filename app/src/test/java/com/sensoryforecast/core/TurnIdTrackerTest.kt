package com.sensoryforecast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TurnIdTrackerTest {

    @Test
    fun 거리가_줄어드는_동안은_같은_ID다() {
        val t = TurnIdTracker()
        val a = t.assign(listOf("TUNNEL:남산1호" to 500.0))
        val b = t.assign(listOf("TUNNEL:남산1호" to 300.0))
        assertEquals(a, b)
    }

    @Test
    fun 거리가_크게_늘면_다음_안내다() {
        val t = TurnIdTracker()
        val a = t.assign(listOf("ROUNDABOUT:" to 40.0))
        val b = t.assign(listOf("ROUNDABOUT:" to 600.0))
        assertNotEquals(a, b)
    }

    @Test
    fun 사라졌다_다시_나오면_새_안내다() {
        val t = TurnIdTracker()
        val a = t.assign(listOf("TOLL:" to 100.0))
        t.assign(emptyList())
        val b = t.assign(listOf("TOLL:" to 90.0))
        assertNotEquals(a, b)
    }

    @Test
    fun 같은_종류_둘은_다른_ID이고_앞을_지나도_뒤의_ID는_그대로다() {
        val t = TurnIdTracker()
        val first = t.assign(listOf("ROUNDABOUT:" to 100.0, "ROUNDABOUT:" to 400.0))
        assertNotEquals(first[0], first[1])
        val after = t.assign(listOf("ROUNDABOUT:" to 380.0))
        assertEquals(first[1], after[0])
    }

    @Test
    fun 앞_안내가_지나도_뒤_안내는_자기_ID를_지킨다() {
        val t = TurnIdTracker()
        val first = t.assign(listOf("ROUNDABOUT:" to 10.0, "ROUNDABOUT:" to 65.0))
        val after = t.assign(listOf("ROUNDABOUT:" to 37.0))
        assertEquals(first[1], after[0])
    }

    @Test
    fun 경로가_바뀌면_가까이_생긴_안내도_새_ID다() {
        val t = TurnIdTracker()
        val before = t.assign(listOf("TOLL:" to 200.0))
        t.reset()
        val after = t.assign(listOf("TOLL:" to 50.0))
        assertNotEquals(before, after)
    }
}
