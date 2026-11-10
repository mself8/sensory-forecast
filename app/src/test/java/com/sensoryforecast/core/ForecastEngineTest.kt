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
        override fun lowerFanTo(level: Int) { calls += "바람:$level" }
        override fun setSeatLight(seat: SeatPosition, colorArgb: Int) { calls += "조명:$seat:${Integer.toHexString(colorArgb)}" }
    }

    private val usualColor = 0xFF00E5FF.toInt()

    private fun card(
        kinds: Set<RoadEventKind> = RoadEventKind.entries.toSet(),
        modes: Set<CueMode> = CueMode.entries.toSet(),
    ) = ChildCard("아이", SeatPosition.REAR_RIGHT, kinds, modes, usualColor)

    private fun ev(id: String, kind: RoadEventKind, m: Double) = RoadEvent(id, kind, m)

    @Test
    fun 멀리_있는_사건은_아직_알리지_않는다() {
        val r = Recorder()
        ForecastEngine(card(), r, r).onEvents(listOf(ev("a", RoadEventKind.SPEED_BUMP, 400.0)))
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun 가까워지면_한_번만_말한다() {
        val r = Recorder()
        val e = ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r)
        e.onEvents(listOf(ev("a", RoadEventKind.ROAD_DAMAGE, 140.0)))
        e.onEvents(listOf(ev("a", RoadEventKind.ROAD_DAMAGE, 120.0)))
        e.onEvents(listOf(ev("a", RoadEventKind.ROAD_DAMAGE, 40.0)))
        assertEquals(listOf("말:곧 덜컹해요."), r.calls)
    }

    @Test
    fun 가까운_과속방지턱은_묶어서_센다() {
        val r = Recorder()
        val e = ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r)
        e.onEvents(listOf(ev("a", RoadEventKind.SPEED_BUMP, 140.0), ev("b", RoadEventKind.SPEED_BUMP, 190.0)))
        e.onEvents(listOf(ev("b", RoadEventKind.SPEED_BUMP, 100.0)))
        assertEquals(listOf("말:곧 덜컹 두 번 해요."), r.calls)
    }

    @Test
    fun 부모가_고르지_않은_사건은_알리지_않는다() {
        val r = Recorder()
        ForecastEngine(card(kinds = setOf(RoadEventKind.TUNNEL)), r, r)
            .onEvents(listOf(ev("a", RoadEventKind.SPEED_BUMP, 30.0)))
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun 터널_전에는_말한_뒤_창문을_닫고_내기로_바꾼다() {
        val r = Recorder()
        ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r)
            .onEvents(listOf(ev("t", RoadEventKind.TUNNEL, 150.0)))
        assertEquals(listOf("말:곧 터널이에요. 어두워져요.", "창문닫기:REAR_RIGHT", "내기:true"), r.calls)
    }

    @Test
    fun 공사_구간에서는_바람도_약하게_한다() {
        val r = Recorder()
        ForecastEngine(card(modes = emptySet()), r, r)
            .onEvents(listOf(ev("c", RoadEventKind.CONSTRUCTION, 100.0)))
        assertEquals(listOf("창문닫기:REAR_RIGHT", "내기:true", "바람:1"), r.calls)
    }

    @Test
    fun 효과음과_조명은_직전에_켜고_지나가면_평소_색으로_돌린다() {
        val r = Recorder()
        val e = ForecastEngine(card(modes = setOf(CueMode.SOUND, CueMode.LIGHT)), r, r)
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
        ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r).onEvents(
            listOf(ev("t", RoadEventKind.TUNNEL, 140.0), ev("a", RoadEventKind.SPEED_BUMP, 80.0))
        )
        assertEquals("말:곧 덜컹해요. 곧 터널이에요. 어두워져요.", r.calls.first())
    }

    @Test
    fun 창문은_말이_끝난_뒤에_닫는다() {
        val r = Recorder().apply { holdSpeech = true }
        ForecastEngine(card(modes = setOf(CueMode.VOICE)), r, r)
            .onEvents(listOf(ev("t", RoadEventKind.TUNNEL, 150.0)))
        assertEquals(listOf("말:곧 터널이에요. 어두워져요."), r.calls)
        r.finishSpeech()
        assertEquals(listOf("말:곧 터널이에요. 어두워져요.", "창문닫기:REAR_RIGHT", "내기:true"), r.calls)
    }

    @Test
    fun 앞_사건이_지나는_순간_다음_사건_조명이_켜지면_깜빡이지_않는다() {
        val r = Recorder()
        val e = ForecastEngine(card(modes = setOf(CueMode.LIGHT)), r, r)
        e.onEvents(listOf(ev("a", RoadEventKind.SPEED_BUMP, 45.0), ev("b", RoadEventKind.SPEED_BUMP, 85.0)))
        e.onEvents(listOf(ev("b", RoadEventKind.SPEED_BUMP, 40.0)))
        assertEquals(listOf("조명:REAR_RIGHT:ffffb300", "조명:REAR_RIGHT:ffffb300"), r.calls)
        e.onEvents(emptyList())
        assertEquals("조명:REAR_RIGHT:ff00e5ff", r.calls.last())
    }

    @Test
    fun 끝내면_바꾼_조명을_돌리고_늦게_온_일은_하지_않는다() {
        val r = Recorder().apply { holdSpeech = true }
        val e = ForecastEngine(card(), r, r)
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
        val e = ForecastEngine(card(), r, r)
        e.paused = true
        e.onEvents(listOf(ev("a", RoadEventKind.SPEED_BUMP, 30.0)))
        assertTrue(r.calls.isEmpty())
    }
}
