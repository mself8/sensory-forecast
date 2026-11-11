package com.sensoryforecast.ui

import android.content.Context
import com.sensoryforecast.core.ChildCard
import com.sensoryforecast.core.CueMode
import com.sensoryforecast.core.RoadEventKind
import com.sensoryforecast.core.SeatPosition
import com.sensoryforecast.core.childCardOf
import com.sensoryforecast.core.toStrings

/** 아이 카드를 기기 안에 저장합니다. 아이 정보는 차 밖으로 보내지 않습니다. */
class ChildCardStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("child_card", Context.MODE_PRIVATE)

    fun load(): ChildCard =
        childCardOf(prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap(), DEFAULT_CARD)

    fun save(card: ChildCard) {
        prefs.edit().apply { card.toStrings().forEach { (k, v) -> putString(k, v) } }.apply()
    }

    companion object {
        /** 아이 카드를 아직 저장하지 않았을 때 쓰는 카드입니다. */
        val DEFAULT_CARD = ChildCard(
            name = "아이",
            seat = SeatPosition.REAR_RIGHT,
            alertKinds = RoadEventKind.entries.toSet(),
            cueModes = CueMode.entries.toSet(),
            lightColorArgb = 0xFF00E5FF.toInt(),
        )
    }
}
