package com.sensoryforecast.core

/**
 * 내비 회전 안내에는 ID가 없어서, 같은 안내를 두 번 알리지 않도록 ID를 붙입니다.
 *
 * 안내를 종류와 설명으로 묶어 key로 삼고, 직전 목록과 짝을 짓습니다.
 * - 차는 앞으로만 가므로 같은 안내의 남은 거리는 줄어듭니다. 직전 거리보다 [noiseMeters] 넘게 늘지 않고,
 *   [maxTravelMeters] 넘게 줄지 않은 안내 가운데 가장 가까운 것과 짝을 짓고 그 ID를 물려받습니다.
 * - 짝이 없으면 새 ID를 받습니다. 목록에서 사라진 안내는 잊습니다.
 * - 경로가 바뀌면 [reset]으로 모두 잊습니다.
 */
class TurnIdTracker(
    private val noiseMeters: Double = 10.0,
    private val maxTravelMeters: Double = 300.0,
) {
    private var nextId = 0
    private var previous: Map<String, List<Pair<String, Double>>> = emptyMap()

    fun reset() {
        previous = emptyMap()
    }

    /** [turns]의 key와 남은 거리를 받아 같은 순서로 ID를 돌려줍니다. */
    fun assign(turns: List<Pair<String, Double>>): List<String> {
        val ids = arrayOfNulls<String>(turns.size)
        val next = mutableMapOf<String, MutableList<Pair<String, Double>>>()

        turns.indices.groupBy { turns[it].first }.forEach { (key, indices) ->
            val unmatched = previous[key].orEmpty().sortedBy { it.second }.toMutableList()
            indices.sortedBy { turns[it].second }.forEach { i ->
                val d = turns[i].second
                val match = unmatched.firstOrNull { (_, p) -> p >= d - noiseMeters && p - d <= maxTravelMeters }
                val id = match?.first ?: "$key#${nextId++}"
                if (match != null) unmatched.remove(match)
                ids[i] = id
                next.getOrPut(key) { mutableListOf() } += id to d
            }
        }
        previous = next
        return ids.map { it!! }
    }
}
