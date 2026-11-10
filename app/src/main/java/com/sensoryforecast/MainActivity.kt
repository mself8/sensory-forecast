package com.sensoryforecast

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 차가 서 있을 때 예보를 켜고 끄는 화면입니다. 아이 카드 화면이 생기면 여기에 붙입니다.
 * 주행 중에는 화면 조작이 막히므로, 예보는 ForecastService가 음성·소리·조명으로 전합니다.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this).apply {
            text = "예보가 꺼져 있어요"
            textSize = 24f
            gravity = Gravity.CENTER
        }

        fun button(label: String, action: String, done: String) = Button(this).apply {
            text = label
            textSize = 24f
            setOnClickListener {
                val intent = ForecastService.intent(this@MainActivity, action)
                if (action == ForecastService.ACTION_STOP) startService(intent) else startForegroundService(intent)
                status.text = done
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            addView(TextView(context).apply {
                text = getString(R.string.app_name)
                textSize = 40f
                gravity = Gravity.CENTER
            })
            addView(status)
            addView(button("내비와 함께 시작", ForecastService.ACTION_START_NAVI, "내비와 함께 예보 중이에요"))
            addView(button("시험 주행 시작", ForecastService.ACTION_START_DEMO, "시험 주행으로 예보 중이에요"))
            addView(button("멈추기", ForecastService.ACTION_STOP, "예보가 꺼져 있어요"))
        })
    }
}
