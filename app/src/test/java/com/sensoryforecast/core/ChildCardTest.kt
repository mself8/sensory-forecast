package com.sensoryforecast.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ChildCardTest {

    private val base = ChildCard("아이", SeatPosition.REAR_RIGHT, RoadEventKind.entries.toSet(), CueMode.entries.toSet(), 0)

    @Test
    fun 저장했다가_읽으면_같은_카드다() {
        val card = base.copy(
            seat = SeatPosition.REAR_LEFT,
            alertKinds = setOf(RoadEventKind.TUNNEL, RoadEventKind.SPEED_BUMP),
            cueModes = setOf(CueMode.SOUND),
        )
        assertEquals(card, childCardOf(card.toStrings(), base))
    }

    @Test
    fun 저장한_값이_없으면_기본_카드다() {
        assertEquals(base, childCardOf(emptyMap(), base))
    }

    @Test
    fun 모두_끈_자극은_빈_목록으로_읽는다() {
        assertEquals(emptySet<RoadEventKind>(), childCardOf(mapOf("kinds" to ""), base).alertKinds)
    }

    @Test
    fun 모르는_이름은_버린다() {
        val card = childCardOf(mapOf("seat" to "ROOF", "kinds" to "TUNNEL,FLYING", "modes" to "VOICE,SMELL"), base)
        assertEquals(base.seat, card.seat)
        assertEquals(setOf(RoadEventKind.TUNNEL), card.alertKinds)
        assertEquals(setOf(CueMode.VOICE), card.cueModes)
    }
}
