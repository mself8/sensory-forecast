package com.sensoryforecast

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * 시작 화면 자리입니다. 아이 카드 화면과 상태 화면이 생기면 바꿉니다.
 * 주행 중에는 화면 조작이 막히므로, 예보는 화면이 아니라 음성·소리·조명으로 전합니다.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 32f
                gravity = Gravity.CENTER
            }
        )
    }
}
