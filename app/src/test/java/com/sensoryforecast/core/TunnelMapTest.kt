package com.sensoryforecast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunnelMapTest {

    // 위도 37도 근처에서 북쪽으로 1m는 위도 약 0.000009도입니다.
    private val lat0 = 37.5
    private val lon0 = 127.0
    private fun north(m: Double) = lat0 + m / 111_320.0

    /** 남북으로 놓인 양방향 터널 하나입니다. 남쪽 입구는 북쪽으로, 북쪽 입구는 남쪽으로 들어갑니다. */
    private val southGate = TunnelEntrance(north(200.0), lon0, 0.0, 500.0, "가터널")
    private val northGate = TunnelEntrance(north(700.0), lon0, 180.0, 500.0, "가터널")

    @Test
    fun 앞쪽_입구를_찾는다() {
        val map = TunnelMap(listOf(southGate, northGate))
        assertEquals(southGate, map.find(lat0, lon0, heading = 0.0, aheadMeters = 200.0))
    }

    @Test
    fun 반대_방향_입구는_고르지_않는다() {
        // 북쪽에서 남쪽으로 달리면 북쪽 입구로 들어갑니다.
        val map = TunnelMap(listOf(southGate, northGate))
        assertEquals(northGate, map.find(north(900.0), lon0, heading = 180.0, aheadMeters = 200.0))
    }

    @Test
    fun 뒤에_있는_입구는_고르지_않는다() {
        val map = TunnelMap(listOf(southGate))
        assertNull(map.find(north(400.0), lon0, heading = 0.0, aheadMeters = 200.0))
    }

    @Test
    fun 거리가_크게_다르면_고르지_않는다() {
        val map = TunnelMap(listOf(southGate))
        assertNull(map.find(lat0, lon0, heading = 0.0, aheadMeters = 900.0))
    }

    @Test
    fun 이름이_맞는_입구를_먼저_고른다() {
        val other = TunnelEntrance(north(190.0), lon0, 0.0, 80.0, "나지하차도")
        val map = TunnelMap(listOf(southGate, other))
        assertEquals(other, map.find(lat0, lon0, heading = 0.0, aheadMeters = 200.0, name = "나지하차도"))
        assertEquals(southGate, map.find(lat0, lon0, heading = 0.0, aheadMeters = 200.0, name = "가터널"))
    }

    @Test
    fun 머리말을_건너뛰고_읽는다() {
        val map = TunnelMap.parse(
            sequenceOf(
                "# 국내 도로 터널 입구",
                "# 위도\t경도\t방향(도)\t길이(m)\t이름",
                "${north(200.0)}\t$lon0\t0\t500\t가터널",
            )
        )
        assertEquals(500.0, map.find(lat0, lon0, heading = 0.0, aheadMeters = 200.0)!!.lengthMeters, 0.0)
    }

    @Test
    fun 실제_자료에서_인제양양터널을_양방향_모두_찾는다() {
        // 각 입구에서 300m 앞에 차를 두고 들어가는 방향을 보게 합니다. 시험은 app 폴더에서 돕니다.
        val map = java.io.File("src/main/assets/tunnels_kr.tsv").bufferedReader().useLines { TunnelMap.parse(it) }
        val west = map.find(37.97009, 128.39939, heading = 55.0, aheadMeters = 300.0)!!
        val east = map.find(37.98402, 128.52484, heading = 227.0, aheadMeters = 300.0)!!
        assertEquals("인제양양터널", west.name)
        assertEquals("인제양양터널", east.name)
        assertEquals(10_946.0, west.lengthMeters, 5.0)
        assertEquals(10_947.0, east.lengthMeters, 5.0)
    }
}
