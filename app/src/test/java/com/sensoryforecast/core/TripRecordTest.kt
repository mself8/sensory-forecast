package com.sensoryforecast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripRecordTest {

    @Test
    fun 줄로_썼다가_읽으면_같은_기록이다() {
        val a = TripRecord(1_000, 61_000, 5, 1, 4)
        val b = TripRecord(2_000, 9_000, 0, 0, null)
        assertEquals(a, TripRecord.fromLine(a.toLine()))
        assertEquals(b, TripRecord.fromLine(b.toLine()))
    }

    @Test
    fun 깨진_줄은_읽지_않는다() {
        assertNull(TripRecord.fromLine("1000\t2000\t3"))
        assertNull(TripRecord.fromLine("1000\t2000\t3\t0\t9"))
        assertNull(TripRecord.fromLine("x\t2000\t3\t0\t-"))
    }

    @Test
    fun 시작_시각이_같은_기록에만_평가를_매긴다() {
        val list = listOf(TripRecord(1, 2, 3, 0), TripRecord(5, 6, 1, 0))
        assertEquals(listOf(TripRecord(1, 2, 3, 0), TripRecord(5, 6, 1, 0, 2)), list.rated(5, 2))
    }

    @Test
    fun 벨트는_매인_뒤_풀릴_때만_센다() {
        val c = TripCounter(0)
        c.onBelt(false)          // 처음부터 풀려 있으면 세지 않습니다.
        c.onBelt(true)
        c.onBelt(false)
        c.onBelt(false)          // 같은 상태가 다시 와도 한 번입니다.
        c.onBelt(true)
        c.onBelt(false)
        c.onForewarned()
        assertEquals(TripRecord(0, 10, 1, 2), c.finish(10))
    }
}
