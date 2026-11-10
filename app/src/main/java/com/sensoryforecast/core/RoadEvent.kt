package com.sensoryforecast.core

/**
 * 앞길에서 아이가 몸으로 느낄 사건입니다.
 * 차 SDK의 형식과 떼어 둔 앱 안의 형식이라, 차 없이도 판단 로직을 시험할 수 있습니다.
 */
enum class RoadEventKind(val sense: Sense, val announced: Boolean = true) {
    SPEED_BUMP(Sense.MOTION),        // 내비 주의 지점 SPEED_BUMP
    ROAD_DAMAGE(Sense.MOTION),       // 내비 주의 지점 ROAD_DAMAGE_ALERT
    DOWNHILL(Sense.MOTION),          // 내비 주의 지점 DOWNHILL_CAUTION_AREA
    SHARP_TURN(Sense.MOTION),        // 회전 안내 TURN_SHARP_L, TURN_SHARP_R
    U_TURN(Sense.MOTION),            // 회전 안내 TURN_UTURN
    ROUNDABOUT(Sense.MOTION),        // 회전 안내 TURN_ENTER_ROUNDABOUT_*
    TUNNEL(Sense.LIGHT_AND_SOUND),   // 회전 안내 TURN_TUNNEL
    UNDERPASS(Sense.LIGHT_AND_SOUND),// 회전 안내 TURN_ENTER_UNDER_*
    RAILROAD_CROSSING(Sense.SOUND),  // 내비 주의 지점 RAILROAD_CROSSING
    CONSTRUCTION(Sense.SOUND),       // 내비 주의 지점 HIGHWAY_CONSTRUCTION
    TOLLGATE(Sense.STOP),            // 회전 안내 TURN_TOLL
    REST_AREA(Sense.STOP),           // 회전 안내 TURN_ENTER_SERVICEAREA, TURN_RESTAREA
    ARRIVAL(Sense.STOP),             // 회전 안내 TURN_ARRIVE
    REROUTE(Sense.CHANGE),           // 내비 이벤트 onReRouteEnded

    /** 터널 출구입니다. 아이에게 알리지 않고, 지나가면 터널 앞에서 바꾼 공조를 되돌리는 데만 씁니다. */
    TUNNEL_EXIT(Sense.CHANGE, announced = false),
}

/** 터널 사건 ID로 그 터널 출구 사건의 ID를 만듭니다. 사건을 만드는 쪽과 엔진이 같은 규칙을 씁니다. */
fun exitIdOf(tunnelId: String): String = "$tunnelId:exit"

/** 사건이 주로 건드리는 감각입니다. 대응 방식을 고를 때 씁니다. */
enum class Sense { MOTION, LIGHT_AND_SOUND, SOUND, STOP, CHANGE }

/**
 * @param id 같은 사건을 두 번 알리지 않으려고 씁니다. 내비 주의 지점은 SDK가 준 ID를 그대로 씁니다.
 * @param distanceMeters 지금 위치에서 사건까지 남은 거리입니다.
 */
data class RoadEvent(
    val id: String,
    val kind: RoadEventKind,
    val distanceMeters: Double,
)
