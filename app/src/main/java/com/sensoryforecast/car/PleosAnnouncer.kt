package com.sensoryforecast.car

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import com.sensoryforecast.R
import com.sensoryforecast.core.Announcer
import com.sensoryforecast.core.RoadEventKind
import com.sensoryforecast.core.Sense

/**
 * 짧은 말은 Pleos 음성 합성으로, 효과음은 res/raw의 소리 파일로 냅니다. 소리 파일은 tools/make_cues.py가 만듭니다.
 * 음성 합성은 기기 안에서 도는 방식이라 네트워크가 없어도 됩니다.
 *
 * 말이 끝나면 넘겨받은 일을 메인 스레드에서 차례로 부릅니다.
 * 끝났다는 신호가 [DONE_TIMEOUT_MILLIS] 안에 오지 않아도 부릅니다. 창문처럼 보호를 위한 일이 막히지 않게 하려는 것입니다.
 */
class PleosAnnouncer(context: Context) : Announcer {

    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private val cues = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val cueIds: Map<Sense, Int> = mapOf(
        Sense.MOTION to R.raw.cue_motion,
        Sense.LIGHT_AND_SOUND to R.raw.cue_light_and_sound,
        Sense.SOUND to R.raw.cue_sound,
        Sense.STOP to R.raw.cue_stop,
        Sense.CHANGE to R.raw.cue_change,
    ).mapValues { (_, res) -> cues.load(context.applicationContext, res, 1) }
    private var ready = false
    private var released = false
    private val waiting = ArrayDeque<Pair<String, () -> Unit>>()
    private val afterSpeech = ArrayDeque<Runnable>()

    private val events = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() {
            main.post(::finishOne)
        }

        override fun onStop() {
            main.post(::finishOne)
        }

        override fun onError(error: ErrorType) {
            Log.w(TAG, "말하기 실패: $error")
            main.post(::finishOne)
        }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    init {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                main.post {
                    if (released) return@post
                    tts.addEventListener(events)
                    ready = true
                    Log.i(TAG, "음성 합성 준비됨")
                    while (waiting.isNotEmpty()) waiting.removeFirst().let { (text, done) -> speak(text, done) }
                }
            }

            override fun onFailure(error: ErrorType) {
                Log.w(TAG, "음성 합성 준비 실패: $error")
                main.post { while (waiting.isNotEmpty()) waiting.removeFirst().second() }
            }
        })
    }

    override fun speak(text: String, onDone: () -> Unit) {
        if (released) return
        if (!ready) {
            waiting.addLast(text to onDone)
            return
        }
        Log.i(TAG, "말: $text")
        val once = object : Runnable {
            var ran = false
            override fun run() {
                if (ran) return
                ran = true
                main.removeCallbacks(this)
                afterSpeech.remove(this)
                onDone()
            }
        }
        afterSpeech.addLast(once)
        main.postDelayed(once, DONE_TIMEOUT_MILLIS)
        tts.speak(text, true)
    }

    private fun finishOne() {
        afterSpeech.firstOrNull()?.run()
    }

    override fun playCue(kind: RoadEventKind) {
        if (released) return
        Log.i(TAG, "효과음: $kind")
        cueIds[kind.sense]?.let { cues.play(it, 1f, 1f, 1, 0, 1f) }
    }

    fun release() {
        released = true
        afterSpeech.forEach { main.removeCallbacks(it) }
        afterSpeech.clear()
        waiting.clear()
        if (ready) {
            tts.stop()
            tts.removeEventListener(events)
        }
        tts.release()
        cues.release()
    }

    companion object {
        private const val TAG = "SensoryForecast"
        private const val DONE_TIMEOUT_MILLIS = 8000L
    }
}
