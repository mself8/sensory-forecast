package com.sensoryforecast.car

import android.content.Context
import android.os.Handler
import android.util.Log
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import com.sensoryforecast.core.RoadEvent
import com.sensoryforecast.core.RoadEventKind
import com.sensoryforecast.core.RoadEventSource
import com.sensoryforecast.core.TunnelMap
import com.sensoryforecast.core.TurnIdTracker
import com.sensoryforecast.core.exitIdOf

/**
 * Pleos 내비가 아는 앞길 사건을 RoadEvent로 바꿔 넘깁니다.
 * 주의 지점과 회전 안내는 따로 오므로, 둘 중 하나가 바뀔 때마다 합친 목록을 넘깁니다.
 * NaviHelper는 바인더 스레드에서 콜백을 부르므로, 상태는 [handler]의 스레드에서만 바꿉니다.
 *
 * 내비는 터널까지 거리만 알려 줍니다. 그래서 터널 안내를 처음 받으면 지금 위치로 OSM 터널 목록에서 길이를 찾고,
 * 그때 거리계 값을 기억해 둡니다. 거리계가 터널까지 거리와 길이를 더한 만큼 늘면 출구를 지난 것입니다.
 */
class PleosRoadEventSource(private val context: Context, private val handler: Handler) : RoadEventSource {

    private val navi = NaviHelper(context.applicationContext)
    private val vehicle = Vehicle(context.applicationContext)
    private val tunnelMap by lazy {
        context.assets.open(TUNNEL_ASSET).bufferedReader().useLines { TunnelMap.parse(it) }
    }
    private val turnIds = TurnIdTracker()
    private var cautions: List<RoadEvent> = emptyList()
    private var turns: List<RoadEvent> = emptyList()
    private var onEvents: ((List<RoadEvent>) -> Unit)? = null

    private var here: CurrentLocationInfo? = null
    private var odometerKm: Double? = null

    /** 길이를 찾은 터널입니다. 찾았을 때 거리계 값과 그때 출구까지 남은 거리를 둡니다. */
    private class Exit(val atKm: Double, val meters: Double)
    private val exits = mutableMapOf<String, Exit>()
    private val looked = mutableSetOf<String>()

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
                known.forEachIndexed { i, (t, kind) ->
                    if (kind == RoadEventKind.TUNNEL || kind == RoadEventKind.UNDERPASS) findExit(ids[i], t.distance, t.description)
                }
                emit()
            }
        }

        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            handler.post { here = info }
        }

        /** 경로가 바뀌면 회전 안내를 새로 받으므로 ID 짝짓기를 처음부터 합니다. */
        override fun onReRouteEnded() {
            handler.post {
                turnIds.reset()
                emit(RoadEvent("reroute:${System.currentTimeMillis()}", RoadEventKind.REROUTE, 0.0))
            }
        }
    }

    /** 터널 안에서는 내비 목록이 바뀌지 않을 수 있어, 거리계가 바뀔 때도 목록을 다시 넘깁니다. */
    private val odometer = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(km: Float?) {
            handler.post {
                if (km != null) {
                    odometerKm = km.toDouble()
                    if (exits.isNotEmpty()) emit()
                }
            }
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "거리계 알림 실패", e)
        }
    }

    override fun start(onEvents: (List<RoadEvent>) -> Unit) {
        this.onEvents = onEvents
        vehicle.initialize()
        vehicle.getOdometer().registerDistanceDriven(odometer)
        vehicle.getOdometer().getCurrentDistanceDriven(
            { km -> handler.post { if (km != null && odometerKm == null) odometerKm = km.toDouble() } },
            { e -> Log.w(TAG, "거리계 읽기 실패", e) },
        )
        navi.initialize(
            { navi.addListener(listener); Log.i(TAG, "내비 연결됨") },
            { e -> Log.w(TAG, "내비 연결 실패", e) },
        )
    }

    override fun stop() {
        navi.removeListener(listener)
        navi.release()
        vehicle.getOdometer().unregisterDistanceDriven(odometer)
        vehicle.release()
        onEvents = null
    }

    /** 위치나 거리계를 아직 모르면 다음 목록에서 다시 해 봅니다. */
    private fun findExit(id: String, aheadMeters: Double, name: String) {
        if (id in looked) return
        val loc = here ?: return
        val km = odometerKm ?: return
        looked += id
        val tunnel = tunnelMap.find(loc.latitude, loc.longitude, loc.heading, aheadMeters, name)
        if (tunnel == null) {
            Log.i(TAG, "터널 길이 못 찾음: '$name' ${aheadMeters.toInt()}m 앞")
            return
        }
        exits[id] = Exit(km, aheadMeters + tunnel.lengthMeters)
        Log.i(TAG, "터널 찾음: ${tunnel.name} ${tunnel.lengthMeters.toInt()}m, 출구까지 ${(aheadMeters + tunnel.lengthMeters).toInt()}m")
    }

    private fun emit(extra: RoadEvent? = null) {
        val exitEvents = mutableListOf<RoadEvent>()
        val km = odometerKm
        if (km != null) {
            val it = exits.entries.iterator()
            while (it.hasNext()) {
                val (id, x) = it.next()
                val left = x.meters - (km - x.atKm) * 1000
                if (left > 0) exitEvents += RoadEvent(exitIdOf(id), RoadEventKind.TUNNEL_EXIT, left) else it.remove()
            }
        }
        onEvents?.invoke(cautions + turns + exitEvents + listOfNotNull(extra))
    }

    companion object {
        private const val TAG = "SensoryForecast"
        private const val TUNNEL_ASSET = "tunnels_kr.tsv"

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
