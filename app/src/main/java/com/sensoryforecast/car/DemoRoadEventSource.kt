package com.sensoryforecast.car

import android.os.Handler
import com.sensoryforecast.core.RoadEvent
import com.sensoryforecast.core.RoadEventKind
import com.sensoryforecast.core.RoadEventSource

/**
 * 시험용 길 위 사건입니다. 내비 없이 정해진 순서로 사건을 흘려보냅니다.
 * 에뮬레이터에서 내비가 지도 서버에 붙지 못할 때 판단 로직과 차 제어를 시험하려고 씁니다.
 * 시연에 쓰면 시험용 입력이라고 밝힙니다.
 *
 * @param metersPerSecond 가상 차 속도입니다. 20이면 시속 72km입니다.
 */
class DemoRoadEventSource(
    private val handler: Handler,
    private val metersPerSecond: Double = 20.0,
) : RoadEventSource {

    private val script = listOf(
        RoadEvent("demo:bump-1", RoadEventKind.SPEED_BUMP, 300.0),
        RoadEvent("demo:bump-2", RoadEventKind.SPEED_BUMP, 340.0),
        RoadEvent("demo:tunnel", RoadEventKind.TUNNEL, 800.0),
        RoadEvent("demo:construction", RoadEventKind.CONSTRUCTION, 1400.0),
        RoadEvent("demo:arrival", RoadEventKind.ARRIVAL, 2000.0),
    )
    private var travelled = 0.0
    private var onEvents: ((List<RoadEvent>) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            val ahead = script
                .map { it.copy(distanceMeters = it.distanceMeters - travelled) }
                .filter { it.distanceMeters > 0 }
            onEvents?.invoke(ahead)
            if (ahead.isEmpty()) return
            travelled += metersPerSecond
            handler.postDelayed(this, 1000)
        }
    }

    override fun start(onEvents: (List<RoadEvent>) -> Unit) {
        this.onEvents = onEvents
        travelled = 0.0
        handler.post(tick)
    }

    override fun stop() {
        handler.removeCallbacks(tick)
        onEvents = null
    }
}
