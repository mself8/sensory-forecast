package com.sensoryforecast.core

/**
 * 차 속도를 사건까지 남은 거리가 줄어드는 빠르기로 구합니다. 내비 자료와 차 SDK에 현재 속도가 없어서입니다.
 *
 * 주의 지점과 회전 안내는 따로 갱신됩니다. 그래서 한쪽만 바뀐 목록에서는 다른 쪽 거리가 예전 값 그대로 옵니다.
 * 이런 사건을 멈춘 것으로 세지 않도록, 거리가 실제로 줄어든 사건만 씁니다.
 * 나누는 시간은 그 사건의 거리가 마지막으로 바뀐 때부터 잽니다.
 */
class SpeedEstimator(
    private val staleAfterMillis: Long = 3_000,
    private val maxMetersPerSecond: Double = 70.0,
) {
    private class Mark(val meters: Double, val atMillis: Long)

    private var marks: Map<String, Mark> = emptyMap()
    private var latest: Double? = null
    private var latestAtMillis = 0L

    fun update(events: List<RoadEvent>, nowMillis: Long) {
        val samples = mutableListOf<Double>()
        marks = events.associate { e ->
            val before = marks[e.id]
            e.id to when {
                before == null || e.distanceMeters > before.meters -> Mark(e.distanceMeters, nowMillis)
                e.distanceMeters == before.meters -> before
                else -> {
                    val seconds = (nowMillis - before.atMillis) / 1000.0
                    if (seconds > 0) {
                        val v = (before.meters - e.distanceMeters) / seconds
                        // 내비가 위치를 바로잡으며 거리가 한꺼번에 줄면 말이 안 되는 속도가 나옵니다. 그런 값은 버립니다.
                        if (v <= maxMetersPerSecond) samples += v
                    }
                    Mark(e.distanceMeters, nowMillis)
                }
            }
        }
        if (samples.isNotEmpty()) {
            latest = samples.average()
            latestAtMillis = nowMillis
        }
    }

    /** 초속 몇 m인지 돌려줍니다. 한 번도 못 구했으면 null입니다. 최근 [staleAfterMillis] 동안 거리가 줄지 않았으면 서 있다고 보고 0입니다. */
    fun metersPerSecond(nowMillis: Long): Double? {
        val v = latest ?: return null
        return if (nowMillis - latestAtMillis > staleAfterMillis) 0.0 else v
    }
}
