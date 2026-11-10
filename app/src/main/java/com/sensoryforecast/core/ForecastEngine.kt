package com.sensoryforecast.core

/**
 * 길 위 사건 목록을 받을 때마다 아이에게 무엇을 알리고 차 안을 어떻게 바꿀지 정합니다.
 * 차 SDK를 직접 부르지 않고 Announcer와 CabinControl 약속만 씁니다.
 *
 * 알림은 두 번에 나눕니다. 설계 문서의 "말은 조금 일찍, 효과음과 조명은 직전에"를 따릅니다.
 * 1. 말과 차 안 바꾸기: 사건까지 [voiceWithinMeters] 이하일 때. 창문은 말한 뒤에 닫습니다.
 * 2. 효과음과 조명: 사건까지 [cueWithinMeters] 이하일 때.
 *
 * 내비 데이터에 차 속도가 없어 시간 대신 거리로 정합니다. 150m는 시속 54km에서 약 10초 전입니다.
 *
 * @param bundleWithinMeters 말할 때 첫 사건 뒤 이 거리 안의 같은 종류 사건을 묶어서 셉니다.
 */
class ForecastEngine(
    private val card: ChildCard,
    private val announcer: Announcer,
    private val cabin: CabinControl,
    private val voiceWithinMeters: Double = 150.0,
    private val cueWithinMeters: Double = 50.0,
    private val bundleWithinMeters: Double = 100.0,
) {
    /** 조용한 방 모드에서 예고를 잠시 멈춥니다. */
    var paused: Boolean = false

    private val spokenIds = mutableSetOf<String>()
    private val cuedIds = mutableSetOf<String>()

    /** 조명을 바꾸게 한 사건들입니다. 모두 지나가면 아이 카드의 평소 색으로 돌립니다. */
    private val lightHeldBy = mutableSetOf<String>()

    fun onEvents(events: List<RoadEvent>) {
        restoreLightIfPassed(events)
        if (paused) return

        val wanted = events.filter { it.kind in card.alertKinds }

        dueGroups(wanted, voiceWithinMeters, spokenIds, bundleWithinMeters)?.let { groups ->
            if (CueMode.VOICE in card.cueModes) {
                announcer.speak(groups.map { phraseFor(it.kind, it.ids.size) }.distinct().joinToString(" "))
            }
            groups.forEach { adjustCabin(it.kind) }
        }

        dueGroups(wanted, cueWithinMeters, cuedIds, bundle = 0.0)?.let { groups ->
            val nearest = groups.first()
            if (CueMode.SOUND in card.cueModes) announcer.playCue(nearest.kind)
            if (CueMode.LIGHT in card.cueModes) {
                cabin.setSeatLight(card.seat, lightColorFor(nearest.kind.sense))
                lightHeldBy += groups.flatMap { it.ids }
            }
        }
    }

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

    private fun adjustCabin(kind: RoadEventKind) {
        when (kind) {
            RoadEventKind.TUNNEL, RoadEventKind.UNDERPASS -> {
                cabin.closeWindow(card.seat)
                cabin.setRecirculation(true)
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

    private fun restoreLightIfPassed(events: List<RoadEvent>) {
        if (lightHeldBy.isEmpty()) return
        val upcoming = events.filter { it.distanceMeters > 0 }.map { it.id }.toSet()
        lightHeldBy.retainAll(upcoming)
        if (lightHeldBy.isEmpty()) cabin.setSeatLight(card.seat, card.lightColorArgb)
    }

    companion object {
        /** "바람 약하게"의 단계입니다. 지금 바람이 이보다 약하면 그대로 둡니다. */
        const val QUIET_FAN_LEVEL = 1
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
    RoadEventKind.TOLLGATE -> "곧 멈춰요. 돈 내는 곳이에요."
    RoadEventKind.REST_AREA -> "곧 휴게소에 들러요."
    RoadEventKind.ARRIVAL -> "곧 내려요."
    RoadEventKind.REROUTE -> "길이 바뀌었어요."
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
