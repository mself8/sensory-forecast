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
