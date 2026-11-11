package com.sensoryforecast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastEngineTest {

    private class Recorder : Announcer, CabinControl {
        val calls = mutableListOf<String>()

        /** true면 말이 끝났다는 신호를 바로 보내지 않고 [finishSpeech]를 부를 때까지 미룹니다. */
        var holdSpeech = false
        private val held = mutableListOf<() -> Unit>()
        fun finishSpeech() { held.forEach { it() }; held.clear() }

        override fun speak(text: String, onDone: () -> Unit) {
            calls += "말:$text"
            if (holdSpeech) held += onDone else onDone()
        }
        override fun playCue(kind: RoadEventKind) { calls += "소리:$kind" }
        override fun closeWindow(seat: SeatPosition) { calls += "창문닫기:$seat" }
        override fun setRecirculation(on: Boolean) { calls += "내기:$on" }
        override fun restoreRecirculation() { calls += "내기되돌림" }
        override fun lowerFanTo(level: Int) { calls += "바람:$level" }
        override fun setSeatLight(seat: SeatPosition, colorArgb: Int) { calls += "조명:$seat:${Integer.toHexString(colorArgb)}" }
    }

    private val usualColor = 0xFF00E5FF.toInt()

    /** 시험용 시계입니다. 움직이지 않으면 속도를 모르므로 정해 둔 거리 150m와 50m로 알립니다. */
    private var now = 0L

    private fun card(
        kinds: Set<RoadEventKind> = RoadEventKind.entries.toSet(),
        modes: Set<CueMode> = CueMode.entries.toSet(),
    ) = ChildCard("아이", SeatPosition.REAR_RIGHT, kinds, modes, usualColor)

    private fun ev(id: String, kind: RoadEventKind, m: Double) = RoadEvent(id, kind, m)

    @Test
    fun 멀리_있는_사건은_아직_알리지_않는다() {
        val r = Recorder()
        ForecastEngine(card(), r, r, clock = { now }).onEvents(listOf(ev("a", RoadEventKind.SPEED_BUMP, 400.0)))
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun 가까워지면_한_번만_말한다() {
        val r = Recorder()
        val e = ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r, clock = { now })
        e.onEvents(listOf(ev("a", RoadEventKind.ROAD_DAMAGE, 140.0)))
        e.onEvents(listOf(ev("a", RoadEventKind.ROAD_DAMAGE, 120.0)))
        e.onEvents(listOf(ev("a", RoadEventKind.ROAD_DAMAGE, 40.0)))
        assertEquals(listOf("말:곧 덜컹해요."), r.calls)
    }

    @Test
    fun 가까운_과속방지턱은_묶어서_센다() {
        val r = Recorder()
        val e = ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r, clock = { now })
        e.onEvents(listOf(ev("a", RoadEventKind.SPEED_BUMP, 140.0), ev("b", RoadEventKind.SPEED_BUMP, 190.0)))
        e.onEvents(listOf(ev("b", RoadEventKind.SPEED_BUMP, 100.0)))
        assertEquals(listOf("말:곧 덜컹 두 번 해요."), r.calls)
    }

    @Test
    fun 부모가_고르지_않은_사건은_알리지_않는다() {
        val r = Recorder()
        ForecastEngine(card(kinds = setOf(RoadEventKind.TUNNEL)), r, r, clock = { now })
            .onEvents(listOf(ev("a", RoadEventKind.SPEED_BUMP, 30.0)))
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun 터널_전에는_말한_뒤_창문을_닫고_내기로_바꾼다() {
        val r = Recorder()
        ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r, clock = { now })
            .onEvents(listOf(ev("t", RoadEventKind.TUNNEL, 150.0)))
        assertEquals(listOf("말:곧 터널이에요. 어두워져요.", "창문닫기:REAR_RIGHT", "내기:true"), r.calls)
    }

    @Test
    fun 공사_구간에서는_바람도_약하게_한다() {
        val r = Recorder()
        ForecastEngine(card(modes = emptySet()), r, r, clock = { now })
            .onEvents(listOf(ev("c", RoadEventKind.CONSTRUCTION, 100.0)))
        assertEquals(listOf("창문닫기:REAR_RIGHT", "내기:true", "바람:1"), r.calls)
    }

    @Test
    fun 효과음과_조명은_직전에_켜고_지나가면_평소_색으로_돌린다() {
        val r = Recorder()
        val e = ForecastEngine(card(modes = setOf(CueMode.SOUND, CueMode.LIGHT)), r, r, clock = { now })
        e.onEvents(listOf(ev("a", RoadEventKind.SPEED_BUMP, 120.0)))
        assertTrue(r.calls.isEmpty())
        e.onEvents(listOf(ev("a", RoadEventKind.SPEED_BUMP, 45.0)))
        assertEquals(listOf("소리:SPEED_BUMP", "조명:REAR_RIGHT:ffffb300"), r.calls)
        e.onEvents(emptyList())
        assertEquals("조명:REAR_RIGHT:ff00e5ff", r.calls.last())
        assertEquals(3, r.calls.size)
    }

    @Test
    fun 여러_사건이_함께_오면_한_문장으로_묶는다() {
        val r = Recorder()
        ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r, clock = { now }).onEvents(
            listOf(ev("t", RoadEventKind.TUNNEL, 140.0), ev("a", RoadEventKind.SPEED_BUMP, 80.0))
        )
        assertEquals("말:곧 덜컹해요. 곧 터널이에요. 어두워져요.", r.calls.first())
    }

    @Test
    fun 창문은_말이_끝난_뒤에_닫는다() {
        val r = Recorder().apply { holdSpeech = true }
        ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r, clock = { now })
            .onEvents(listOf(ev("t", RoadEventKind.TUNNEL, 150.0)))
        assertEquals(listOf("말:곧 터널이에요. 어두워져요."), r.calls)
        r.finishSpeech()
        assertEquals(listOf("말:곧 터널이에요. 어두워져요.", "창문닫기:REAR_RIGHT", "내기:true"), r.calls)
    }

    @Test
    fun 앞_사건이_지나는_순간_다음_사건_조명이_켜지면_깜빡이지_않는다() {
        val r = Recorder()
        val e = ForecastEngine(card(modes = setOf(CueMode.LIGHT)), r, r, clock = { now })
        e.onEvents(listOf(ev("a", RoadEventKind.SPEED_BUMP, 45.0), ev("b", RoadEventKind.SPEED_BUMP, 85.0)))
        e.onEvents(listOf(ev("b", RoadEventKind.SPEED_BUMP, 40.0)))
        assertEquals(listOf("조명:REAR_RIGHT:ffffb300", "조명:REAR_RIGHT:ffffb300"), r.calls)
        e.onEvents(emptyList())
        assertEquals("조명:REAR_RIGHT:ff00e5ff", r.calls.last())
    }

    @Test
    fun 끝내면_바꾼_조명을_돌리고_늦게_온_일은_하지_않는다() {
        val r = Recorder().apply { holdSpeech = true }
        val e = ForecastEngine(card(), r, r, clock = { now })
        e.onEvents(listOf(ev("t", RoadEventKind.TUNNEL, 40.0)))
        e.close()
        r.finishSpeech()
        e.onEvents(listOf(ev("c", RoadEventKind.CONSTRUCTION, 40.0)))
        assertEquals(
            listOf("말:곧 터널이에요. 어두워져요.", "소리:TUNNEL", "조명:REAR_RIGHT:ff2979ff", "조명:REAR_RIGHT:ff00e5ff"),
            r.calls,
        )
    }

    @Test
    fun 조용한_방_모드에서는_알리지_않는다() {
        val r = Recorder()
        val e = ForecastEngine(card(), r, r, clock = { now })
        e.paused = true
        e.onEvents(listOf(ev("a", RoadEventKind.SPEED_BUMP, 30.0)))
        assertTrue(r.calls.isEmpty())
    }

    /** 1초마다 [step]m씩 다가가며 [from]부터 [to]까지 목록을 넣습니다. */
    private fun drive(e: ForecastEngine, id: String, kind: RoadEventKind, from: Double, to: Double, step: Double) {
        var m = from
        while (m >= to) {
            e.onEvents(listOf(ev(id, kind, m)))
            now += 1000
            m -= step
        }
    }

    @Test
    fun 느리게_달리면_더_가까이서_말한다() {
        // 초속 10m, 시속 36km입니다. 다음 갱신까지 1초를 더해 9초 거리인 90m 안에 들면 말합니다.
        val r = Recorder()
        val e = ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r, clock = { now })
        drive(e, "a", RoadEventKind.ROAD_DAMAGE, from = 200.0, to = 100.0, step = 10.0)
        assertTrue(r.calls.isEmpty())
        e.onEvents(listOf(ev("a", RoadEventKind.ROAD_DAMAGE, 90.0)))
        assertEquals(listOf("말:곧 덜컹해요."), r.calls)
    }

    @Test
    fun 빠르게_달리면_더_멀리서_말한다() {
        // 초속 30m, 시속 108km라 270m 안에 들면 말합니다. 거리로만 정할 때의 150m보다 이릅니다.
        val r = Recorder()
        val e = ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r, clock = { now })
        drive(e, "t", RoadEventKind.TUNNEL, from = 300.0, to = 300.0, step = 30.0)
        assertTrue(r.calls.isEmpty())
        e.onEvents(listOf(ev("t", RoadEventKind.TUNNEL, 270.0)))
        assertEquals("말:곧 터널이에요. 어두워져요.", r.calls.first())
    }

    @Test
    fun 효과음도_속도로_정한다() {
        // 초속 20m라 3초에 1초를 더한 80m 안에 들면 효과음을 냅니다.
        val r = Recorder()
        val e = ForecastEngine(card(modes = setOf(CueMode.SOUND)), r, r, clock = { now })
        drive(e, "a", RoadEventKind.SPEED_BUMP, from = 200.0, to = 100.0, step = 20.0)
        assertTrue(r.calls.isEmpty())
        e.onEvents(listOf(ev("a", RoadEventKind.SPEED_BUMP, 80.0)))
        assertEquals(listOf("소리:SPEED_BUMP"), r.calls)
    }

    @Test
    fun 서_있으면_바로_앞에_온_사건만_말한다() {
        // 초속 10m로 달리다 멈췄습니다. 3초 넘게 거리가 그대로면 시속 10km로 세어 25m 안에 들어야 말합니다.
        val r = Recorder()
        val e = ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r, clock = { now })
        drive(e, "toll", RoadEventKind.TOLLGATE, from = 500.0, to = 490.0, step = 10.0)
        now += 4000
        e.onEvents(listOf(ev("toll", RoadEventKind.TOLLGATE, 490.0), ev("bump", RoadEventKind.SPEED_BUMP, 60.0)))
        assertTrue(r.calls.isEmpty())
        e.onEvents(
            listOf(
                ev("toll", RoadEventKind.TOLLGATE, 490.0),
                ev("bump", RoadEventKind.SPEED_BUMP, 60.0),
                ev("hole", RoadEventKind.ROAD_DAMAGE, 20.0),
            )
        )
        assertEquals(listOf("말:곧 덜컹해요."), r.calls)
    }

    @Test
    fun 거리가_그대로인_사건은_속도_계산에_넣지_않는다() {
        // 회전 안내는 주의 지점과 따로 갱신되어 예전 거리가 그대로 올 때가 있습니다.
        // 그걸 멈춘 것으로 세면 속도가 반으로 줄어 말이 늦어집니다.
        val r = Recorder()
        val e = ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r, clock = { now })
        e.onEvents(listOf(ev("toll", RoadEventKind.TOLLGATE, 900.0), ev("bump", RoadEventKind.SPEED_BUMP, 260.0)))
        now += 1000
        e.onEvents(listOf(ev("toll", RoadEventKind.TOLLGATE, 900.0), ev("bump", RoadEventKind.SPEED_BUMP, 230.0)))
        assertEquals(listOf("말:곧 덜컹해요."), r.calls)
    }

    @Test
    fun 터널_출구를_지나면_내기_순환을_되돌린다() {
        val r = Recorder()
        val e = ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r, clock = { now })
        val exit = exitIdOf("t")
        e.onEvents(listOf(ev("t", RoadEventKind.TUNNEL, 140.0), ev(exit, RoadEventKind.TUNNEL_EXIT, 440.0)))
        assertEquals(listOf("말:곧 터널이에요. 어두워져요.", "창문닫기:REAR_RIGHT", "내기:true"), r.calls)
        e.onEvents(listOf(ev(exit, RoadEventKind.TUNNEL_EXIT, 40.0)))
        assertEquals(3, r.calls.size)
        e.onEvents(emptyList())
        assertEquals("내기되돌림", r.calls.last())
        assertEquals(4, r.calls.size)
    }

    @Test
    fun 출구를_모르는_터널은_내기_순환을_그대로_둔다() {
        val r = Recorder()
        val e = ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r, clock = { now })
        e.onEvents(listOf(ev("t", RoadEventKind.TUNNEL, 140.0)))
        e.onEvents(emptyList())
        assertEquals(listOf("말:곧 터널이에요. 어두워져요.", "창문닫기:REAR_RIGHT", "내기:true"), r.calls)
    }

    @Test
    fun 터널_출구는_아이에게_알리지_않는다() {
        val r = Recorder()
        ForecastEngine(card(), r, r, clock = { now })
            .onEvents(listOf(ev(exitIdOf("t"), RoadEventKind.TUNNEL_EXIT, 30.0)))
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun 미리_알린_자극_묶음마다_기록에_알린다() {
        var count = 0
        val e = ForecastEngine(card(), Recorder(), Recorder(), clock = { now }, onForewarned = { count++ })
        e.onEvents(listOf(ev("a", RoadEventKind.SPEED_BUMP, 120.0), ev("b", RoadEventKind.SPEED_BUMP, 160.0), ev("t", RoadEventKind.TUNNEL, 140.0)))
        e.onEvents(listOf(ev("a", RoadEventKind.SPEED_BUMP, 120.0), ev("b", RoadEventKind.SPEED_BUMP, 160.0), ev("t", RoadEventKind.TUNNEL, 140.0)))
        // 방지턱 둘은 한 묶음, 터널은 다른 묶음입니다. 같은 자극은 두 번 세지 않습니다.
        assertEquals(2, count)
    }
}
