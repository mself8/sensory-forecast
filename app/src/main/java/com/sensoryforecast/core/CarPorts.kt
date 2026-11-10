package com.sensoryforecast.core

/*
 * 판단 로직이 차에 기대는 일을 세 가지로 나눈 약속입니다.
 * 진짜 차에서는 car 패키지의 Pleos SDK 구현이, 시험에서는 가짜 구현이 이 약속을 채웁니다.
 */

/** 앞길 사건을 받습니다. Pleos 구현은 NaviHelper의 onCautionPointInfo와 onTBTInfo를 RoadEvent로 바꿉니다. */
interface RoadEventSource {
    fun start(onEvents: (List<RoadEvent>) -> Unit)
    fun stop()
}

/** 아이에게 알립니다. */
interface Announcer {
    fun speak(text: String)
    fun playCue(kind: RoadEventKind)
}

/** 차 안 환경을 바꿉니다. 좌석은 달리는 중에 움직이지 않으므로 여기에 넣지 않습니다. */
interface CabinControl {
    fun closeWindow(seat: SeatPosition)
    fun setRecirculation(on: Boolean)
    fun setFanLevel(level: Int)
    fun setSeatLight(seat: SeatPosition, colorArgb: Int)
}
