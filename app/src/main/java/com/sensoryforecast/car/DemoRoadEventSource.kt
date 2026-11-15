package com.sensoryforecast.car

import android.os.Handler
import com.sensoryforecast.core.RoadEvent
import com.sensoryforecast.core.RoadEventKind
import com.sensoryforecast.core.RoadEventSource
import com.sensoryforecast.core.exitIdOf

/**
 * 시험용 길 위 사건입니다. 내비 없이 정해진 순서로 사건을 흘려보냅니다.
 * 에뮬레이터 내비는 차 위치가 움직이지 않아, 판단 로직과 차 제어를 이 입력으로 시험합니다.
 * 시연에 쓰면 시험용 입력이라고 밝힙니다.
 *
 * 두 구간을 차례로 달리고, 구간 사이에는 [PAUSE_MILLIS] 동안 쉽니다. 거리는 모두 실제 길에서 OpenStreetMap 자료로 쟀습니다.
 * 1. 남양주 화도읍 맷돌로, 시속 30km. 과속방지턱 세 개를 넘습니다. tools/demo_video/route/make_town.py 참고.
 * 2. 서울양양고속도로 서울 방향, 시속 90km. 월문1터널 입구 400m 앞에서 출발해 1,227m 터널을 지납니다.
 *    tools/demo_video/route/make_route.py 참고.
 * 같은 거리를 시연 영상의 지도도 씁니다.
 */
class DemoRoadEventSource(private val handler: Handler) : RoadEventSource {

    /** 한 구간입니다. 거리는 구간 출발점부터 잽니다. */
    private class Leg(val metersPerSecond: Double, val lengthMeters: Double, val events: List<RoadEvent>)

    private val legs = listOf(
        Leg(
            30 / 3.6, 480.0,
            listOf(
                RoadEvent("demo:bump1", RoadEventKind.SPEED_BUMP, 200.0),
                RoadEvent("demo:bump2", RoadEventKind.SPEED_BUMP, 266.0),
                RoadEvent("demo:bump3", RoadEventKind.SPEED_BUMP, 420.0),
            ),
        ),
        Leg(
            25.0, 2127.0,
            listOf(
                RoadEvent("demo:tunnel", RoadEventKind.TUNNEL, 400.0),
                RoadEvent(exitIdOf("demo:tunnel"), RoadEventKind.TUNNEL_EXIT, 1627.0),
            ),
        ),
    )
    private var leg = 0
    private var travelled = 0.0
    private var onEvents: ((List<RoadEvent>) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            val current = legs.getOrNull(leg) ?: return
            val ahead = current.events
                .map { it.copy(distanceMeters = it.distanceMeters - travelled) }
                .filter { it.distanceMeters > 0 }
            onEvents?.invoke(ahead)
            travelled += current.metersPerSecond
            if (travelled < current.lengthMeters) {
                handler.postDelayed(this, 1000)
                return
            }
            // 구간 끝에 닿으면 잠시 쉬고 다음 구간을 처음부터 달립니다. 마지막 구간이면 멈춥니다.
            leg += 1
            travelled = 0.0
            if (leg < legs.size) handler.postDelayed(this, PAUSE_MILLIS)
        }
    }

    override fun start(onEvents: (List<RoadEvent>) -> Unit) {
        this.onEvents = onEvents
        leg = 0
        travelled = 0.0
        handler.post(tick)
    }

    override fun stop() {
        handler.removeCallbacks(tick)
        onEvents = null
    }

    companion object {
        /** 구간 사이에 쉬는 시간입니다. 시연 영상은 이 사이를 잘라 냅니다. */
        const val PAUSE_MILLIS = 6_000L
    }
}
