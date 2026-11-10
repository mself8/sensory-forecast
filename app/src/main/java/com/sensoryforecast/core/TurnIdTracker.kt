package com.sensoryforecast.core

/**
 * 내비 회전 안내에는 ID가 없어서, 같은 안내를 두 번 알리지 않도록 번호를 붙입니다.
 *
 * 안내를 종류와 설명으로 묶어 key로 삼고, 가장 가까운 안내의 남은 거리를 기억합니다.
 * - 거리가 줄어드는 동안은 같은 안내로 봅니다.
 * - 가장 가까운 거리가 [passedJumpMeters]보다 크게 늘면 앞 안내를 지난 것으로 보고 번호를 올립니다.
 * - 목록에서 사라졌다가 다시 나오면 새 안내로 봅니다.
 */
class TurnIdTracker(private val passedJumpMeters: Double = 30.0) {
    private val generation = mutableMapOf<String, Int>()
    private val lastNearest = mutableMapOf<String, Double>()

    /** [turns]의 key와 남은 거리를 받아 같은 순서로 ID를 돌려줍니다. */
    fun assign(turns: List<Pair<String, Double>>): List<String> {
        val ids = arrayOfNulls<String>(turns.size)
        val byKey = turns.indices.groupBy { turns[it].first }

        (lastNearest.keys - byKey.keys).forEach { gone ->
            lastNearest.remove(gone)
            generation[gone] = (generation[gone] ?: 0) + 1
        }

        byKey.forEach { (key, indices) ->
            val sorted = indices.sortedBy { turns[it].second }
            val nearest = turns[sorted.first()].second
            var gen = generation[key] ?: 0
            val last = lastNearest[key]
            if (last != null && nearest > last + passedJumpMeters) gen += 1
            generation[key] = gen
            lastNearest[key] = nearest
            sorted.forEachIndexed { order, index -> ids[index] = "$key#${gen + order}" }
        }
        return ids.map { it!! }
    }
}
