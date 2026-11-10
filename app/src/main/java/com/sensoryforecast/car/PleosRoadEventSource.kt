package com.sensoryforecast.car

import android.content.Context
import android.os.Handler
import android.util.Log
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.sensoryforecast.core.RoadEvent
import com.sensoryforecast.core.RoadEventKind
import com.sensoryforecast.core.RoadEventSource
import com.sensoryforecast.core.TurnIdTracker

/**
 * Pleos 내비가 아는 앞길 사건을 RoadEvent로 바꿔 넘깁니다.
 * 주의 지점과 회전 안내는 따로 오므로, 둘 중 하나가 바뀔 때마다 합친 목록을 넘깁니다.
 * NaviHelper는 바인더 스레드에서 콜백을 부르므로, 상태는 [handler]의 스레드에서만 바꿉니다.
 */
class PleosRoadEventSource(context: Context, private val handler: Handler) : RoadEventSource {

    private val navi = NaviHelper(context.applicationContext)
    private val turnIds = TurnIdTracker()
    private var cautions: List<RoadEvent> = emptyList()
    private var turns: List<RoadEvent> = emptyList()
    private var onEvents: ((List<RoadEvent>) -> Unit)? = null

    private val listener = object : NaviHelperEventListener {
        override fun onCautionPointInfo(list: List<CautionPoint>) {
            handler.post {
                cautions = list.mapNotNull { p ->
                    cautionKind(p.type)?.let { RoadEvent("caution:${p.id}", it, p.remainDistance.toDouble()) }
                }
                emit()
            }
        }

        override fun onTBTInfo(list: List<TBTInfo>) {
            handler.post {
                val known = list.mapNotNull { t -> turnKind(t.type)?.let { t to it } }
                val ids = turnIds.assign(known.map { (t, _) -> "${t.type.name}:${t.description}" to t.distance })
                turns = known.mapIndexed { i, (t, kind) -> RoadEvent(ids[i], kind, t.distance) }
                emit()
            }
        }

        /** 경로가 바뀌면 회전 안내를 새로 받으므로 ID 짝짓기를 처음부터 합니다. */
        override fun onReRouteEnded() {
            handler.post {
                turnIds.reset()
                emit(RoadEvent("reroute:${System.currentTimeMillis()}", RoadEventKind.REROUTE, 0.0))
            }
        }
    }

    override fun start(onEvents: (List<RoadEvent>) -> Unit) {
        this.onEvents = onEvents
        navi.initialize(
            { navi.addListener(listener); Log.i(TAG, "내비 연결됨") },
            { e -> Log.w(TAG, "내비 연결 실패", e) },
        )
    }

    override fun stop() {
        navi.removeListener(listener)
        navi.release()
        onEvents = null
    }

    private fun emit(extra: RoadEvent? = null) {
        onEvents?.invoke(cautions + turns + listOfNotNull(extra))
    }

    companion object {
        private const val TAG = "SensoryForecast"

        fun cautionKind(type: CautionPointType): RoadEventKind? = when (type) {
            CautionPointType.SPEED_BUMP -> RoadEventKind.SPEED_BUMP
            CautionPointType.ROAD_DAMAGE_ALERT -> RoadEventKind.ROAD_DAMAGE
            CautionPointType.DOWNHILL_CAUTION_AREA -> RoadEventKind.DOWNHILL
            CautionPointType.RAILROAD_CROSSING -> RoadEventKind.RAILROAD_CROSSING
            CautionPointType.HIGHWAY_CONSTRUCTION -> RoadEventKind.CONSTRUCTION
            else -> null
        }

        fun turnKind(type: TurnType): RoadEventKind? = when {
            type == TurnType.TURN_SHARP_L || type == TurnType.TURN_SHARP_R -> RoadEventKind.SHARP_TURN
            type == TurnType.TURN_UTURN -> RoadEventKind.U_TURN
            type.name.startsWith("TURN_ENTER_ROUNDABOUT") -> RoadEventKind.ROUNDABOUT
            type == TurnType.TURN_TUNNEL -> RoadEventKind.TUNNEL
            type.name.startsWith("TURN_ENTER_UNDER") -> RoadEventKind.UNDERPASS
            type == TurnType.TURN_TOLL -> RoadEventKind.TOLLGATE
            type == TurnType.TURN_ENTER_SERVICEAREA || type == TurnType.TURN_RESTAREA -> RoadEventKind.REST_AREA
            type == TurnType.TURN_ARRIVE -> RoadEventKind.ARRIVAL
            else -> null
        }
    }
}
