package com.sensoryforecast.core

/** 사건을 아이에게 알리는 방식입니다. 아이마다 여러 개를 고를 수 있습니다. */
enum class CueMode { VOICE, SOUND, LIGHT }

/**
 * 부모가 차가 서 있을 때 한 번 정하는 아이 설정입니다.
 *
 * @param seat 아이가 앉는 자리입니다. 창문과 조명을 이 자리 기준으로 바꿉니다.
 * @param alertKinds 미리 알릴 사건 종류입니다. 알림이 많으면 그 자체가 자극이라 부모가 골라서 줄입니다.
 * @param cueModes 알리는 방식입니다.
 * @param lightColorArgb 평소 아이 자리 조명 색입니다. 예고가 끝나면 이 색으로 돌아갑니다.
 */
data class ChildCard(
    val name: String,
    val seat: SeatPosition,
    val alertKinds: Set<RoadEventKind>,
    val cueModes: Set<CueMode>,
    val lightColorArgb: Int,
)

/** 앱 안에서 쓰는 좌석 위치입니다. 차 SDK의 SeatArea로 바꾸는 일은 car 패키지가 맡습니다. */
enum class SeatPosition { FRONT_RIGHT, REAR_LEFT, REAR_RIGHT }

/** 저장할 때 쓰는 글자 형태입니다. 이름과 평소 조명 색은 아직 화면에서 바꾸지 않아 저장하지 않습니다. */
fun ChildCard.toStrings(): Map<String, String> = mapOf(
    "seat" to seat.name,
    "kinds" to alertKinds.joinToString(",") { it.name },
    "modes" to cueModes.joinToString(",") { it.name },
)

/** 저장된 값이 없거나 모르는 이름이면 [base]의 값을 쓰거나 그 이름만 버립니다. 앱이 바뀌어도 예전 카드를 읽을 수 있게 하려는 것입니다. */
fun childCardOf(strings: Map<String, String>, base: ChildCard): ChildCard = base.copy(
    seat = strings["seat"]?.let { s -> SeatPosition.entries.find { it.name == s } } ?: base.seat,
    alertKinds = strings["kinds"]?.let { namesIn(it, RoadEventKind.entries) } ?: base.alertKinds,
    cueModes = strings["modes"]?.let { namesIn(it, CueMode.entries) } ?: base.cueModes,
)

private fun <T : Enum<T>> namesIn(csv: String, all: List<T>): Set<T> =
    csv.split(',').mapNotNull { n -> all.find { it.name == n } }.toSet()
