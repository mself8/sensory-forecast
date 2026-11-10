package com.sensoryforecast.car

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import com.sensoryforecast.core.Announcer
import com.sensoryforecast.core.RoadEventKind
import com.sensoryforecast.core.Sense

/**
 * 짧은 말은 Pleos 음성 합성으로, 효과음은 안드로이드 기본 신호음으로 냅니다.
 * 음성 합성은 기기 안에서 도는 방식이라 네트워크가 없어도 됩니다.
 */
class PleosAnnouncer(context: Context) : Announcer {

    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private val tones = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
    private var ready = false
    private var pending: String? = null

    init {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                ready = true
                Log.i(TAG, "음성 합성 준비됨")
                pending?.let { speak(it) }
                pending = null
            }

            override fun onFailure(error: ErrorType) {
                Log.w(TAG, "음성 합성 준비 실패: $error")
            }
        })
    }

    override fun speak(text: String) {
        Log.i(TAG, "말: $text")
        if (ready) tts.speak(text, true) else pending = text
    }

    override fun playCue(kind: RoadEventKind) {
        Log.i(TAG, "효과음: $kind")
        tones.startTone(toneFor(kind.sense), CUE_MILLIS)
    }

    fun release() {
        tts.stop()
        tts.release()
        tones.release()
    }

    private fun toneFor(sense: Sense): Int = when (sense) {
        Sense.MOTION -> ToneGenerator.TONE_PROP_BEEP2
        Sense.LIGHT_AND_SOUND -> ToneGenerator.TONE_PROP_ACK
        Sense.SOUND -> ToneGenerator.TONE_PROP_BEEP
        Sense.STOP -> ToneGenerator.TONE_PROP_PROMPT
        Sense.CHANGE -> ToneGenerator.TONE_PROP_NACK
    }

    companion object {
        private const val TAG = "SensoryForecast"
        private const val CUE_MILLIS = 400
    }
}
