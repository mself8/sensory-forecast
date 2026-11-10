package com.sensoryforecast.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * 터널 한 방향의 입구입니다.
 * @param bearing 입구로 들어갈 때 차가 향하는 방향입니다. 북쪽 0도, 시계 방향입니다.
 */
data class TunnelEntrance(
    val lat: Double,
    val lon: Double,
    val bearing: Double,
    val lengthMeters: Double,
    val name: String,
)

/**
 * 내비가 "터널까지 몇 m"만 알려 주고 길이는 알려 주지 않아서, 공개 지도 자료로 길이를 찾습니다.
 * 자료는 tools/osm_tunnels.py가 OpenStreetMap에서 만든 국내 도로 터널 입구 목록입니다.
 *
 * 찾는 기준
 * 1. 입구가 지금 위치에서 내비가 말한 거리쯤에 있어야 합니다. 길이 굽으면 직선거리가 짧아지므로 짧은 쪽은 넉넉히 봅니다.
 * 2. 입구가 차 앞쪽에 있어야 합니다.
 * 3. 입구로 들어가는 방향이 차 방향과 비슷해야 합니다. 양방향 터널의 반대쪽 입구를 고르지 않게 합니다.
 * 여러 개가 맞으면 거리 차이와 방향 차이가 작은 것을 고르고, 내비가 준 이름이 들어 있으면 그것을 먼저 고릅니다.
 */
class TunnelMap(entrances: List<TunnelEntrance>) {

    private val cells: Map<Pair<Int, Int>, List<TunnelEntrance>> = entrances.groupBy { cellOf(it.lat, it.lon) }

    fun find(lat: Double, lon: Double, heading: Double, aheadMeters: Double, name: String = ""): TunnelEntrance? {
        val reach = aheadMeters + GPS_SLACK_METERS
        val span = (reach / CELL_METERS).toInt() + 1
        val (ci, cj) = cellOf(lat, lon)
        val near = (ci - span..ci + span).flatMap { i -> (cj - span..cj + span).flatMap { j -> cells[i to j].orEmpty() } }

        return near.mapNotNull { e ->
            val (east, north) = offsetMeters(lat, lon, e.lat, e.lon)
            val d = hypot(east, north)
            if (d > reach || d < aheadMeters * MIN_STRAIGHT_RATIO - GPS_SLACK_METERS) return@mapNotNull null
            if (d > GPS_SLACK_METERS && angleBetween(bearingOf(east, north), heading) > MAX_AHEAD_DEGREES) return@mapNotNull null
            val turn = angleBetween(e.bearing, heading)
            if (turn > MAX_TURN_DEGREES) return@mapNotNull null
            val named = name.isNotBlank() && e.name.isNotBlank() && (name.contains(e.name) || e.name.contains(name))
            val score = abs(aheadMeters - d) + turn * METERS_PER_DEGREE - if (named) NAME_BONUS_METERS else 0.0
            e to score
        }.minByOrNull { it.second }?.first
    }

    companion object {
        private const val CELL_DEGREES = 0.01
        private const val CELL_METERS = 860.0          // 국내 북쪽 끝 위도 38.6도에서 경도 0.01도의 길이입니다. 가장 짧은 값을 써서 칸을 넉넉히 봅니다.
        private const val GPS_SLACK_METERS = 40.0
        private const val MIN_STRAIGHT_RATIO = 0.6
        private const val MAX_AHEAD_DEGREES = 60.0
        private const val MAX_TURN_DEGREES = 60.0
        private const val METERS_PER_DEGREE = 2.0
        private const val NAME_BONUS_METERS = 1000.0

        /** tools/osm_tunnels.py가 만든 TSV를 읽습니다. #으로 시작하는 줄은 머리말입니다. */
        fun parse(lines: Sequence<String>): TunnelMap = TunnelMap(
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                val f = line.split('\t')
                TunnelEntrance(f[0].toDouble(), f[1].toDouble(), f[2].toDouble(), f[3].toDouble(), f.getOrElse(4) { "" }.trim())
            }.toList()
        )

        private fun cellOf(lat: Double, lon: Double) =
            floor(lat / CELL_DEGREES).toInt() to floor(lon / CELL_DEGREES).toInt()

        /** 몇백 m 안에서는 평면으로 봐도 충분합니다. 동쪽과 북쪽으로 몇 m인지 돌려줍니다. */
        private fun offsetMeters(lat: Double, lon: Double, toLat: Double, toLon: Double): Pair<Double, Double> {
            val north = (toLat - lat) * METERS_PER_LAT_DEGREE
            val east = (toLon - lon) * METERS_PER_LAT_DEGREE * cos(lat * PI / 180)
            return east to north
        }

        private const val METERS_PER_LAT_DEGREE = 111_320.0

        private fun bearingOf(east: Double, north: Double) = (atan2(east, north) * 180 / PI + 360) % 360

        private fun angleBetween(a: Double, b: Double): Double {
            val d = abs(a - b) % 360
            return if (d > 180) 360 - d else d
        }
    }
}
