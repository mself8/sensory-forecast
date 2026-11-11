package com.sensoryforecast.ui

import android.content.Context
import com.sensoryforecast.core.TripRecord
import com.sensoryforecast.core.rated
import java.io.File

/** 이동 기록을 기기 안 파일에 한 줄씩 둡니다. 아이 기록은 차 밖으로 보내지 않습니다. */
class TripStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "trips.tsv")

    fun all(): List<TripRecord> =
        if (file.exists()) file.readLines().mapNotNull { TripRecord.fromLine(it) } else emptyList()

    fun add(record: TripRecord) {
        file.appendText(record.toLine() + "\n")
    }

    fun rate(startedAtMillis: Long, rating: Int) {
        file.writeText(all().rated(startedAtMillis, rating).joinToString("") { it.toLine() + "\n" })
    }
}
