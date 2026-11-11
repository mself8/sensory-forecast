package com.sensoryforecast

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
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
import com.sensoryforecast.ui.ChildCardStore

/**
 * 차가 서 있을 때 아이 카드를 정하고 예보를 켜고 끄는 화면입니다.
 * 주행 중에는 화면 조작이 막히므로, 예보는 ForecastService가 음성·소리·조명으로 전합니다.
 * 왼쪽은 아이 카드, 오른쪽은 시작과 멈추기입니다. 카드는 바꿀 때마다 저장하고, 예보를 시작할 때 읽습니다.
 */
class MainActivity : Activity() {

    private lateinit var store: ChildCardStore
    private lateinit var card: ChildCard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ChildCardStore(this)
        card = store.load()

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
            }
        }

        addView(text(getString(R.string.app_name), 40f, bold = true).apply { gravity = Gravity.CENTER })
        addView(status)
        addView(button("내비와 함께 시작", ForecastService.ACTION_START_NAVI, "내비와 함께 예보 중이에요"))
        addView(button("시험 주행 시작", ForecastService.ACTION_START_DEMO, "시험 주행으로 예보 중이에요"))
        addView(button("멈추기", ForecastService.ACTION_STOP, "예보가 꺼져 있어요"))
        // 터널 길이 자료의 출처 표시입니다. ODbL이 요구합니다.
        addView(text("터널 자료 © OpenStreetMap contributors", 14f).apply { gravity = Gravity.CENTER })
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
