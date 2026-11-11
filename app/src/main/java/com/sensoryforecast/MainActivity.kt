package com.sensoryforecast

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.sensoryforecast.core.ChildCard
import com.sensoryforecast.core.CueMode
import com.sensoryforecast.core.RoadEventKind
import com.sensoryforecast.core.SeatPosition
import com.sensoryforecast.core.Sense
import com.sensoryforecast.core.TripRecord
import com.sensoryforecast.ui.ChildCardStore
import com.sensoryforecast.ui.TripStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 차가 서 있을 때 아이 카드를 정하고 예보를 켜고 끄는 화면입니다.
 * 주행 중에는 화면 조작이 막히므로, 예보는 ForecastService가 음성·소리·조명으로 전합니다.
 * 왼쪽은 아이 카드, 오른쪽은 시작과 멈추기입니다. 카드는 바꿀 때마다 저장하고, 예보를 시작할 때 읽습니다.
 * 멈추면 이동 기록이 저장되고, 부모가 오늘 이동을 1~5점으로 매깁니다. 아래에 지난 이동 세 개를 보여 줍니다.
 */
class MainActivity : Activity() {

    private lateinit var store: ChildCardStore
    private lateinit var card: ChildCard
    private lateinit var trips: TripStore
    private lateinit var ratingRow: LinearLayout
    private lateinit var history: TextView
    private var ratingFor: Long? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ChildCardStore(this)
        card = store.load()
        trips = TripStore(this)

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 32, 48, 32)
            addView(ScrollView(context).apply { addView(cardEditor()) }, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(controls(), LinearLayout.LayoutParams(0, MATCH, 1f))
        })
    }

    private fun cardEditor() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 48, 0)
        addView(text("아이 카드", 32f, bold = true))
        addView(text("차가 서 있을 때 한 번 정해 두세요. 바꾼 내용은 예보를 다시 시작할 때 적용돼요.", 18f))

        addView(section("아이 자리"))
        addView(RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            SEATS.forEach { (seat, label) ->
                addView(RadioButton(context).apply {
                    id = View.generateViewId()
                    text = label
                    textSize = BODY
                    isChecked = card.seat == seat
                    setOnCheckedChangeListener { _, on -> if (on) update(card.copy(seat = seat)) }
                })
            }
        })

        addView(section("미리 알릴 자극"))
        GROUPS.forEach { (sense, label, examples) ->
            val kinds = RoadEventKind.entries.filter { it.announced && it.sense == sense }.toSet()
            addView(CheckBox(context).apply {
                text = "$label · $examples"
                textSize = BODY
                isChecked = card.alertKinds.containsAll(kinds)
                setOnCheckedChangeListener { _, on ->
                    update(card.copy(alertKinds = if (on) card.alertKinds + kinds else card.alertKinds - kinds))
                }
            })
        }

        addView(section("알리는 방식"))
        MODES.forEach { (mode, label) ->
            addView(CheckBox(context).apply {
                text = label
                textSize = BODY
                isChecked = mode in card.cueModes
                setOnCheckedChangeListener { _, on ->
                    update(card.copy(cueModes = if (on) card.cueModes + mode else card.cueModes - mode))
                }
            })
        }
    }

    private fun controls() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val status = text("예보가 꺼져 있어요", 24f).apply { gravity = Gravity.CENTER }

        fun button(label: String, action: String, done: String) = Button(context).apply {
            text = label
            textSize = 24f
            setOnClickListener {
                val intent = ForecastService.intent(this@MainActivity, action)
                if (action == ForecastService.ACTION_STOP) startService(intent) else startForegroundService(intent)
                status.text = done
                // 서비스가 멈추며 기록을 쓴 뒤에 다시 읽습니다.
                main.postDelayed(::refreshTrips, RECORD_DELAY_MILLIS)
            }
        }

        addView(text(getString(R.string.app_name), 40f, bold = true).apply { gravity = Gravity.CENTER })
        addView(status)
        addView(button("내비와 함께 시작", ForecastService.ACTION_START_NAVI, "내비와 함께 예보 중이에요"))
        addView(button("시험 주행 시작", ForecastService.ACTION_START_DEMO, "시험 주행으로 예보 중이에요"))
        addView(button("멈추기", ForecastService.ACTION_STOP, "예보가 꺼져 있어요"))
        ratingRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 0)
            visibility = View.GONE
            addView(text("오늘 이동은 어땠나요?", 22f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                (1..5).forEach { n ->
                    addView(Button(context).apply {
                        text = "${n}점"
                        textSize = 20f
                        setOnClickListener { ratingFor?.let { trips.rate(it, n) }; refreshTrips() }
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
            })
        }
        addView(ratingRow)
        addView(text("지난 이동", 20f, bold = true).apply { setPadding(0, 24, 0, 4) })
        history = text("아직 기록이 없어요", 18f)
        addView(history)
        // 터널 길이 자료의 출처 표시입니다. ODbL이 요구합니다.
        addView(text("터널 자료 © OpenStreetMap contributors", 14f).apply { gravity = Gravity.CENTER; setPadding(0, 24, 0, 0) })
    }

    override fun onResume() {
        super.onResume()
        refreshTrips()
    }

    /** 가장 최근 이동이 평가 전이고 막 끝났으면 평가 줄을 보여 줍니다. */
    private fun refreshTrips() {
        val all = trips.all()
        val last = all.lastOrNull()
        val fresh = last != null && last.rating == null && System.currentTimeMillis() - last.endedAtMillis < RATING_WINDOW_MILLIS
        ratingFor = if (fresh) last!!.startedAtMillis else null
        ratingRow.visibility = if (fresh) View.VISIBLE else View.GONE
        history.text = if (all.isEmpty()) "아직 기록이 없어요" else all.takeLast(3).reversed().joinToString("\n") { describe(it) }
    }

    private fun describe(t: TripRecord): String {
        val minutes = (t.endedAtMillis - t.startedAtMillis) / 60_000
        val length = if (minutes < 1) "1분 미만" else "${minutes}분"
        val rating = t.rating?.let { "평가 ${it}점" } ?: "평가 전"
        return "${DATE.format(Date(t.startedAtMillis))} · $length · 알린 자극 ${t.forewarned} · 벨트 풀림 ${t.beltUnbuckled} · $rating"
    }

    private fun update(next: ChildCard) {
        card = next
        store.save(next)
    }

    private fun section(label: String) = text(label, 24f, bold = true).apply { setPadding(0, 32, 0, 8) }

    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val BODY = 22f
        const val RECORD_DELAY_MILLIS = 800L
        const val RATING_WINDOW_MILLIS = 30 * 60_000L
        val DATE = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)

        val SEATS = listOf(
            SeatPosition.REAR_RIGHT to "뒷좌석 오른쪽",
            SeatPosition.REAR_LEFT to "뒷좌석 왼쪽",
            SeatPosition.FRONT_RIGHT to "조수석",
        )

        /** 효과음 묶음과 같은 다섯 묶음입니다. 같은 묶음은 같은 소리를 냅니다. */
        val GROUPS = listOf(
            Triple(Sense.MOTION, "덜컹·쏠림", "과속방지턱, 도로 파손, 내리막, 급회전, 유턴, 회전교차로"),
            Triple(Sense.LIGHT_AND_SOUND, "터널·지하차도", "어두워지고 소리가 울려요"),
            Triple(Sense.SOUND, "시끄러운 길", "철도 건널목, 공사 구간"),
            Triple(Sense.STOP, "멈춤", "톨게이트, 휴게소, 도착"),
            Triple(Sense.CHANGE, "경로 바뀜", "가던 길이 바뀌어요"),
        )

        val MODES = listOf(
            CueMode.VOICE to "짧은 말",
            CueMode.SOUND to "효과음",
            CueMode.LIGHT to "아이 자리 조명 색",
        )
    }
}
