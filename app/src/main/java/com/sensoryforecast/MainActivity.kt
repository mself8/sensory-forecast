package com.sensoryforecast

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
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
 * 모양은 Pleos 차 설정 화면을 따릅니다. 흰 바탕, 얇은 구분선, 검은 스위치, 검게 채운 선택 칸입니다.
 */
class MainActivity : Activity() {

    private lateinit var store: ChildCardStore
    private lateinit var card: ChildCard
    private lateinit var trips: TripStore
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var ratingBlock: LinearLayout
    private lateinit var history: LinearLayout
    private var ratingFor: Long? = null
    private val seatChips = mutableListOf<Pair<SeatPosition, TextView>>()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ChildCardStore(this)
        card = store.load()
        trips = TripStore(this)

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            addView(ScrollView(context).apply { addView(cardEditor()) }, LinearLayout.LayoutParams(0, MATCH, 1.25f))
            addView(View(context).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(dp(1), MATCH))
            addView(ScrollView(context).apply { addView(controls()) }, LinearLayout.LayoutParams(0, MATCH, 1f))
        })
    }

    // ---------- 아이 카드 ----------

    private fun cardEditor() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(header("아이 카드"))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(8), dp(48), dp(40))
            addView(text("차가 서 있을 때 한 번 정해 두세요. 바꾼 내용은 예보를 다시 시작할 때 적용돼요.", 18f, GREY).apply {
                setPadding(0, dp(20), 0, dp(8))
            })

            addView(section("아이 자리"))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                SEATS.forEachIndexed { i, (seat, label) ->
                    val chip = text(label, 20f, INK).apply {
                        gravity = Gravity.CENTER
                        isClickable = true
                        setOnClickListener { pickSeat(seat) }
                    }
                    seatChips += seat to chip
                    addView(chip, LinearLayout.LayoutParams(0, dp(64), 1f).apply { if (i > 0) leftMargin = dp(12) })
                }
            })
            paintSeats()

            addView(divider(top = 32))
            addView(section("미리 알릴 자극"))
            GROUPS.forEachIndexed { i, (sense, label, examples) ->
                val kinds = RoadEventKind.entries.filter { it.announced && it.sense == sense }.toSet()
                if (i > 0) addView(divider())
                addView(toggleRow(label, examples, card.alertKinds.containsAll(kinds)) { on ->
                    update(card.copy(alertKinds = if (on) card.alertKinds + kinds else card.alertKinds - kinds))
                })
            }

            addView(divider(top = 24))
            addView(section("알리는 방식"))
            MODES.forEachIndexed { i, (mode, label, about) ->
                if (i > 0) addView(divider())
                addView(toggleRow(label, about, mode in card.cueModes) { on ->
                    update(card.copy(cueModes = if (on) card.cueModes + mode else card.cueModes - mode))
                })
            }
        })
    }

    private fun pickSeat(seat: SeatPosition) {
        update(card.copy(seat = seat))
        paintSeats()
    }

    private fun paintSeats() {
        seatChips.forEach { (seat, chip) ->
            val on = card.seat == seat
            chip.background = box(if (on) INK else Color.WHITE, if (on) INK else LINE_STRONG)
            chip.setTextColor(if (on) Color.WHITE else INK)
        }
    }

    // ---------- 예보 켜고 끄기 ----------

    private fun controls() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(header(getString(R.string.app_name)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(32))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                statusDot = View(context)
                addView(statusDot, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(14) })
                status = text("", 22f, INK)
                addView(status)
            })
            showStatus("예보가 꺼져 있어요", running = false)

            fun start(label: String, action: String, done: String, filled: Boolean) =
                button(label, filled) {
                    val intent = ForecastService.intent(this@MainActivity, action)
                    if (action == ForecastService.ACTION_STOP) startService(intent) else startForegroundService(intent)
                    showStatus(done, running = action != ForecastService.ACTION_STOP)
                    // 서비스가 멈추며 기록을 쓴 뒤에 다시 읽습니다.
                    main.postDelayed(::refreshTrips, RECORD_DELAY_MILLIS)
                }

            addView(start("내비와 함께 시작", ForecastService.ACTION_START_NAVI, "내비와 함께 예보 중이에요", filled = true),
                buttonParams(top = 28))
            addView(start("시험 주행 시작", ForecastService.ACTION_START_DEMO, "시험 주행으로 예보 중이에요", filled = false),
                buttonParams(top = 14))
            addView(start("멈추기", ForecastService.ACTION_STOP, "예보가 꺼져 있어요", filled = false),
                buttonParams(top = 14))

            ratingBlock = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                addView(divider(top = 32))
                addView(text("오늘 이동은 어땠나요?", 22f, INK).apply { setPadding(0, dp(24), 0, dp(14)) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    (1..5).forEach { n ->
                        addView(button("${n}점", filled = false, size = 20f) {
                            ratingFor?.let { trips.rate(it, n) }
                            refreshTrips()
                        }, LinearLayout.LayoutParams(0, dp(60), 1f).apply { if (n > 1) leftMargin = dp(10) })
                    }
                })
            }
            addView(ratingBlock)

            addView(divider(top = 32))
            addView(text("지난 이동", 22f, INK).apply { setPadding(0, dp(24), 0, dp(4)) })
            history = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(history)
            // 터널 길이와 시험 경로 자료의 출처 표시입니다. ODbL이 요구합니다.
            addView(text("터널·시험 경로 자료 © OpenStreetMap contributors", 14f, GREY).apply { setPadding(0, dp(28), 0, 0) })
        })
    }

    private fun showStatus(label: String, running: Boolean) {
        status.text = label
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (running) GREEN else LINE_STRONG)
        }
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
        ratingBlock.visibility = if (fresh) View.VISIBLE else View.GONE
        history.removeAllViews()
        if (all.isEmpty()) {
            history.addView(text("아직 기록이 없어요", 18f, GREY).apply { setPadding(0, dp(10), 0, 0) })
            return
        }
        all.takeLast(3).reversed().forEachIndexed { i, t ->
            if (i > 0) history.addView(divider())
            val (when_, what) = describe(t)
            history.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                addView(text(when_, 19f, INK))
                addView(text(what, 17f, GREY).apply { setPadding(0, dp(4), 0, 0) })
            })
        }
    }

    private fun describe(t: TripRecord): Pair<String, String> {
        val minutes = (t.endedAtMillis - t.startedAtMillis) / 60_000
        val length = if (minutes < 1) "1분 미만" else "${minutes}분"
        val rating = t.rating?.let { "평가 ${it}점" } ?: "평가 전"
        return "${DATE.format(Date(t.startedAtMillis))} · $length" to
            "알린 자극 ${t.forewarned} · 벨트 풀림 ${t.beltUnbuckled} · $rating"
    }

    private fun update(next: ChildCard) {
        card = next
        store.save(next)
    }

    // ---------- Pleos 설정 화면을 닮은 부품 ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: Int) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
    }

    /** 화면 맨 위 제목 줄입니다. 설정 화면처럼 아래에 구분선을 둡니다. */
    private fun header(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 28f, INK).apply { setPadding(dp(48), dp(26), dp(48), dp(24)) })
        addView(View(context).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(MATCH, dp(1)))
    }

    private fun section(label: String) = text(label, 26f, INK).apply { setPadding(0, dp(28), 0, dp(18)) }

    private fun divider(top: Int = 0) = View(this).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(top) }
    }

    /** 제목, 회색 설명, 오른쪽 검은 스위치로 된 한 줄입니다. 줄 어디를 눌러도 켜고 끕니다. */
    private fun toggleRow(title: String, about: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(16))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 22f, INK))
                addView(text(about, 18f, GREY).apply { setPadding(0, dp(4), 0, 0) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val (view, flip) = pleosSwitch(checked) { on ->
                onChange(on)
                contentDescription = "$title ${if (on) "켜짐" else "꺼짐"}"
            }
            addView(view, LinearLayout.LayoutParams(dp(SWITCH_W), dp(SWITCH_H)).apply { leftMargin = dp(24) })
            contentDescription = "$title ${if (checked) "켜짐" else "꺼짐"}"
            isClickable = true
            setOnClickListener { flip() }
        }

    /** Pleos 설정 화면의 스위치입니다. 검은 알약 모양 바탕에 흰 동그라미가 오른쪽으로 가면 켜짐입니다. */
    private fun pleosSwitch(checked: Boolean, onChange: (Boolean) -> Unit): Pair<View, () -> Unit> {
        var on = checked
        val track = FrameLayout(this)
        val knob = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        }
        track.addView(knob, FrameLayout.LayoutParams(dp(KNOB), dp(KNOB)).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            leftMargin = dp(KNOB_GAP)
        })
        fun paint(animate: Boolean) {
            track.background = GradientDrawable().apply {
                cornerRadius = dp(SWITCH_H / 2).toFloat()
                setColor(if (on) INK else LINE_STRONG)
            }
            val x = if (on) dp(SWITCH_W - KNOB - 2 * KNOB_GAP).toFloat() else 0f
            if (animate) knob.animate().translationX(x).setDuration(120).start() else knob.translationX = x
        }
        paint(animate = false)
        return track to {
            on = !on
            paint(animate = true)
            onChange(on)
        }
    }

    private fun box(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(4).toFloat()
        setColor(fill)
        setStroke(dp(2), stroke)
    }

    /** 검게 채운 버튼은 주된 동작, 테두리 버튼은 나머지입니다. */
    private fun button(label: String, filled: Boolean, size: Float = 22f, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(if (filled) Color.WHITE else INK)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed),
                if (filled) box(PRESSED_INK, PRESSED_INK) else box(PRESSED, LINE_STRONG))
            addState(intArrayOf(), if (filled) box(INK, INK) else box(Color.WHITE, LINE_STRONG))
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun buttonParams(top: Int) = LinearLayout.LayoutParams(MATCH, dp(72)).apply { topMargin = dp(top) }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val RECORD_DELAY_MILLIS = 800L
        const val RATING_WINDOW_MILLIS = 30 * 60_000L
        const val SWITCH_W = 64
        const val SWITCH_H = 36
        const val KNOB = 28
        const val KNOB_GAP = 4
        val DATE = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)

        // Pleos 설정 화면에서 따온 색입니다.
        val INK = Color.rgb(17, 17, 17)
        val GREY = Color.rgb(128, 128, 128)
        val LINE = Color.rgb(229, 229, 229)
        val LINE_STRONG = Color.rgb(200, 200, 200)
        val PRESSED = Color.rgb(240, 240, 240)
        val PRESSED_INK = Color.rgb(60, 60, 60)
        val GREEN = Color.rgb(34, 197, 94)

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
            Triple(CueMode.VOICE, "짧은 말", "다가오는 일을 한두 마디로 미리 말해요"),
            Triple(CueMode.SOUND, "효과음", "직전에 부드러운 소리로 한 번 더 알려요"),
            Triple(CueMode.LIGHT, "아이 자리 조명 색", "직전에 아이 자리 실내 조명 색을 바꿔요"),
        )
    }
}
