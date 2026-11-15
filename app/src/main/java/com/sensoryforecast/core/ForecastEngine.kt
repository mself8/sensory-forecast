package com.sensoryforecast.core

/**
 * 길 위 사건 목록을 받을 때마다 아이에게 무엇을 알리고 차 안을 어떻게 바꿀지 정합니다.
 * 차 SDK를 직접 부르지 않고 Announcer와 CabinControl 약속만 씁니다.
 *
 * 알림은 두 번에 나눕니다. 설계 문서의 "말은 조금 일찍, 효과음과 조명은 직전에"를 따릅니다.
 * 1. 말과 차 안 바꾸기: 사건까지 [voiceLeadSeconds]초 남았을 때. 창문은 말이 끝난 뒤에 닫습니다.
 * 2. 효과음과 조명: 사건까지 [cueLeadSeconds]초 남았을 때.
 *
 * 남은 시간은 [SpeedEstimator]가 구한 속도로 셉니다.
 * 거리는 1초마다 들어오므로, 다음 갱신 때 이미 그 시간 안쪽이면 이번에 미리 알립니다. 그래서 늦지 않고 많아야 1초 이릅니다.
 * 시속 60km면 말은 150m, 효과음은 67m 안에 들어온 첫 갱신 때 나옵니다.
 * 속도를 아직 모르면 [fallbackVoiceMeters]와 [fallbackCueMeters]를 씁니다.
 * 거의 서 있을 때는 시속 10km로 셉니다. 막힌 길에서 몇 분 먼저 말하지 않게 하려는 것입니다.
 *
 * 터널 앞에서 켠 내기 순환은 그 터널의 출구 사건이 지나가면 되돌립니다. 출구를 모르는 터널은 그대로 둡니다.
 *
 * @param bundleWithinMeters 말할 때 첫 사건 뒤 이 거리 안의 같은 종류 사건을 묶어서 셉니다.
 * @param clock 밀리초 시계입니다. 시험에서 시간을 직접 움직이려고 받습니다.
 * @param onForewarned 자극 묶음 하나를 미리 알릴 때마다 부릅니다. 이동 기록이 셉니다.
 */
class ForecastEngine(
    private val card: ChildCard,
    private val announcer: Announcer,
    private val cabin: CabinControl,
    private val voiceLeadSeconds: Double = 8.0,
    private val cueLeadSeconds: Double = 3.0,
    private val fallbackVoiceMeters: Double = 150.0,
    private val fallbackCueMeters: Double = 50.0,
    private val bundleWithinMeters: Double = 100.0,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onForewarned: () -> Unit = {},
) {
    private val speed = SpeedEstimator()

    /** 조용한 방 모드에서 예고를 잠시 멈춥니다. */
    var paused: Boolean = false

    private val spokenIds = mutableSetOf<String>()
    private val cuedIds = mutableSetOf<String>()

    /** 조명을 바꾸게 한 사건들입니다. 모두 목록에서 사라지면 아이 카드의 평소 색으로 돌립니다. */
    private val lightHeldBy = mutableSetOf<String>()
    private var lightChanged = false
    private var closed = false

    /** 내기 순환을 켜게 한 터널의 출구 사건들입니다. 모두 지나가면 내기 순환을 되돌립니다. */
    private val airHeldBy = mutableSetOf<String>()
    private var lastIds: Set<String> = emptySet()

    fun onEvents(events: List<RoadEvent>) {
        if (closed) return
        val now = clock()
        lastIds = events.map { it.id }.toSet()
        speed.update(events, now)
        if (!paused) announce(events.filter { it.kind.announced && it.kind in card.alertKinds }, speed.metersPerSecond(now))
        restoreLightIfPassed(events)
        restoreAirIfExited()
    }

    /** 예보를 끝낼 때 부릅니다. 바꾼 조명을 평소 색으로 돌리고, 이후 들어오는 일은 하지 않습니다. */
    fun close() {
        if (lightChanged) cabin.setSeatLight(card.seat, card.lightColorArgb)
        lightChanged = false
        closed = true
    }

    private fun announce(wanted: List<RoadEvent>, metersPerSecond: Double?) {
        val voiceWithin = leadMeters(metersPerSecond, voiceLeadSeconds, fallbackVoiceMeters)
        val cueWithin = leadMeters(metersPerSecond, cueLeadSeconds, fallbackCueMeters)

        dueGroups(wanted, voiceWithin, spokenIds, bundleWithinMeters)?.let { groups ->
            groups.forEach { onForewarned() }
            val changeCabin = { if (!closed) groups.forEach { adjustCabin(it) } }
            if (CueMode.VOICE in card.cueModes) {
                announcer.speak(groups.map { phraseFor(it.kind, it.ids.size) }.distinct().joinToString(" "), changeCabin)
            } else {
                changeCabin()
            }
        }

        dueGroups(wanted, cueWithin, cuedIds, bundle = 0.0)?.let { groups ->
            val nearest = groups.first()
            if (CueMode.SOUND in card.cueModes) announcer.playCue(nearest.kind)
            if (CueMode.LIGHT in card.cueModes) {
                cabin.setSeatLight(card.seat, lightColorFor(nearest.kind.sense))
                lightHeldBy += groups.flatMap { it.ids }
                lightChanged = true
            }
        }
    }

    private fun leadMeters(metersPerSecond: Double?, seconds: Double, fallback: Double): Double =
        if (metersPerSecond == null) fallback
        else maxOf(metersPerSecond, SLOWEST_METERS_PER_SECOND) * (seconds + UPDATE_INTERVAL_SECONDS)

    private class Group(val kind: RoadEventKind, val ids: List<String>, val nearest: Double)

    /** 아직 처리하지 않은 사건 가운데 [within] 안에 들어온 것을 종류별로 묶습니다. 없으면 null입니다. */
    private fun dueGroups(
        wanted: List<RoadEvent>,
        within: Double,
        done: MutableSet<String>,
        bundle: Double,
    ): List<Group>? {
        val fresh = wanted.filter { it.id !in done }
        val due = fresh.filter { it.distanceMeters <= within }
        if (due.isEmpty()) return null
        val groups = due.groupBy { it.kind }.map { (kind, list) ->
            val first = list.minOf { it.distanceMeters }
            val members = fresh.filter { it.kind == kind && it.distanceMeters <= maxOf(within, first + bundle) }
            Group(kind, members.map { it.id }, first)
        }.sortedBy { it.nearest }
        groups.forEach { done += it.ids }
        return groups
    }

    private fun adjustCabin(group: Group) {
        when (group.kind) {
            RoadEventKind.TUNNEL, RoadEventKind.UNDERPASS -> {
                cabin.closeWindow(card.seat)
                cabin.setRecirculation(true)
                // 말이 끝난 뒤라 지금 받은 목록에 출구가 있는 터널만 붙잡습니다.
                airHeldBy += group.ids.map(::exitIdOf).filter { it in lastIds }
            }
            RoadEventKind.RAILROAD_CROSSING -> cabin.closeWindow(card.seat)
            RoadEventKind.CONSTRUCTION -> {
                cabin.closeWindow(card.seat)
                cabin.setRecirculation(true)
                cabin.lowerFanTo(QUIET_FAN_LEVEL)
            }
            else -> Unit
        }
    }

    /** 같은 목록에서 새 조명을 켰다면 그 사건이 아직 목록에 있으므로 돌리지 않습니다. 그래서 깜빡이지 않습니다. */
    private fun restoreLightIfPassed(events: List<RoadEvent>) {
        if (!lightChanged) return
        lightHeldBy.retainAll(events.map { it.id }.toSet())
        if (lightHeldBy.isEmpty()) {
            cabin.setSeatLight(card.seat, card.lightColorArgb)
            lightChanged = false
        }
    }

    private fun restoreAirIfExited() {
        if (airHeldBy.isEmpty()) return
        airHeldBy.retainAll(lastIds)
        if (airHeldBy.isEmpty()) cabin.restoreRecirculation()
    }

    companion object {
        /** "바람 약하게"의 단계입니다. 지금 바람이 이보다 약하면 그대로 둡니다. */
        const val QUIET_FAN_LEVEL = 1

        /** 이보다 느리면 이 속도로 셉니다. 시속 10km입니다. */
        const val SLOWEST_METERS_PER_SECOND = 10.0 / 3.6

        /** 사건 거리가 새로 들어오는 간격입니다. 시험 주행은 1초입니다. 내비 간격은 연결되면 확인합니다. */
        const val UPDATE_INTERVAL_SECONDS = 1.0
    }
}

/** 같은 사건에는 늘 같은 문장을 씁니다. 데이터로 모르는 일은 약속하지 않습니다. */
fun phraseFor(kind: RoadEventKind, count: Int = 1): String = when (kind) {
    RoadEventKind.SPEED_BUMP -> if (count <= 1) "곧 덜컹해요." else "곧 덜컹 ${koreanCount(count)} 번 해요."
    RoadEventKind.ROAD_DAMAGE -> "곧 덜컹해요."
    RoadEventKind.DOWNHILL -> "곧 내리막길이에요."
    RoadEventKind.SHARP_TURN, RoadEventKind.U_TURN, RoadEventKind.ROUNDABOUT -> "곧 빙글 돌아요."
    RoadEventKind.TUNNEL, RoadEventKind.UNDERPASS -> "곧 터널이에요. 어두워져요."
    RoadEventKind.RAILROAD_CROSSING -> "곧 땡땡 소리가 나요."
    RoadEventKind.CONSTRUCTION -> "곧 시끄러운 길이에요."
    // 하이패스 차는 서지 않고 지나가고, 앱은 하이패스 카드가 있는지 모릅니다. 그래서 멈춘다고 약속하지 않습니다.
    RoadEventKind.TOLLGATE -> "곧 느려지거나 멈춰요."
    RoadEventKind.REST_AREA -> "곧 휴게소에 들러요."
    RoadEventKind.ARRIVAL -> "곧 내려요."
    RoadEventKind.REROUTE -> "길이 바뀌었어요."
    RoadEventKind.TUNNEL_EXIT -> "" // 알리지 않는 사건이라 쓰이지 않습니다.
}

private fun koreanCount(n: Int): String = when (n) {
    2 -> "두"
    3 -> "세"
    4 -> "네"
    5 -> "다섯"
    else -> n.toString()
}

/** 감각마다 정한 조명 색입니다. 깜빡이지 않고 잠깐 바꿨다가 돌립니다. 시작값이라 시험하며 고칩니다. */
fun lightColorFor(sense: Sense): Int = when (sense) {
    Sense.MOTION -> 0xFFFFB300.toInt()
    Sense.LIGHT_AND_SOUND -> 0xFF2979FF.toInt()
    Sense.SOUND -> 0xFFAA00FF.toInt()
    Sense.STOP -> 0xFF00E676.toInt()
    Sense.CHANGE -> 0xFFFFFFFF.toInt()
}
