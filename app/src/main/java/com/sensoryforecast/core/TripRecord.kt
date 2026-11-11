package com.sensoryforecast.core

/**
 * 이동 한 번의 기록입니다. 예보를 켠 때부터 끈 때까지를 한 번으로 봅니다. 기기 안에만 저장합니다.
 *
 * @param forewarned 미리 알린 자극 묶음 수입니다.
 * @param beltUnbuckled 아이 자리 벨트가 매인 상태에서 풀린 횟수입니다.
 * @param rating 도착 후 부모 평가 1~5입니다. 아직 매기지 않았으면 null입니다.
 */
data class TripRecord(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val forewarned: Int,
    val beltUnbuckled: Int,
    val rating: Int? = null,
) {
    fun toLine(): String = listOf(startedAtMillis, endedAtMillis, forewarned, beltUnbuckled, rating ?: "-").joinToString("\t")

    companion object {
        /** 형식이 맞지 않는 줄은 null입니다. 파일 한 줄이 깨져도 나머지 기록은 읽습니다. */
        fun fromLine(line: String): TripRecord? {
            val f = line.split('\t')
            if (f.size != 5) return null
            return TripRecord(
                startedAtMillis = f[0].toLongOrNull() ?: return null,
                endedAtMillis = f[1].toLongOrNull() ?: return null,
                forewarned = f[2].toIntOrNull() ?: return null,
                beltUnbuckled = f[3].toIntOrNull() ?: return null,
                rating = if (f[4] == "-") null else f[4].toIntOrNull()?.takeIf { it in 1..5 } ?: return null,
            )
        }
    }
}

/** 시작 시각이 [startedAtMillis]인 기록에 평가를 매깁니다. 시작 시각이 이동마다 달라 이름표로 씁니다. */
fun List<TripRecord>.rated(startedAtMillis: Long, rating: Int): List<TripRecord> {
    require(rating in 1..5)
    return map { if (it.startedAtMillis == startedAtMillis) it.copy(rating = rating) else it }
}

/** 이동 중에 수를 셉니다. 벨트는 매인 것을 본 뒤 풀린 경우만 셉니다. 처음 상태를 모를 때 풀림으로 세지 않으려는 것입니다. */
class TripCounter(private val startedAtMillis: Long) {
    private var forewarned = 0
    private var unbuckled = 0
    private var buckled: Boolean? = null

    fun onForewarned() { forewarned++ }

    fun onBelt(isBuckled: Boolean) {
        if (buckled == true && !isBuckled) unbuckled++
        buckled = isBuckled
    }

    fun finish(endedAtMillis: Long) = TripRecord(startedAtMillis, endedAtMillis, forewarned, unbuckled)
}
