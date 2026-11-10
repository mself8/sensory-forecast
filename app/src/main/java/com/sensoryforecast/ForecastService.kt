package com.sensoryforecast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.sensoryforecast.car.DemoRoadEventSource
import com.sensoryforecast.car.PleosAnnouncer
import com.sensoryforecast.car.PleosCabinControl
import com.sensoryforecast.car.PleosRoadEventSource
import com.sensoryforecast.core.ChildCard
import com.sensoryforecast.core.CueMode
import com.sensoryforecast.core.ForecastEngine
import com.sensoryforecast.core.RoadEventKind
import com.sensoryforecast.core.RoadEventSource
import com.sensoryforecast.core.SeatPosition

/**
 * 달리는 동안 예보를 맡는 서비스입니다.
 * 주행 중에는 앱 화면이 막히므로, 사건 받기와 알리기는 화면 없이 여기서 합니다.
 */
class ForecastService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var source: RoadEventSource? = null
    private var announcer: PleosAnnouncer? = null
    private var cabin: PleosCabinControl? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_NAVI -> start(demo = false)
            ACTION_START_DEMO -> start(demo = true)
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(demo: Boolean) {
        stopForecast()
        goForeground(if (demo) "시험 주행으로 예보 중" else "내비와 함께 예보 중")
        val a = PleosAnnouncer(this).also { announcer = it }
        val c = PleosCabinControl(this).also { cabin = it }
        val engine = ForecastEngine(DEFAULT_CARD, a, c)
        source = (if (demo) DemoRoadEventSource(main) else PleosRoadEventSource(this)).also { s ->
            s.start { events ->
                main.post {
                    Log.d(TAG, "사건 ${events.joinToString { "${it.kind}@${it.distanceMeters.toInt()}m" }}")
                    engine.onEvents(events)
                }
            }
        }
        Log.i(TAG, if (demo) "시험 주행 시작" else "내비 예보 시작")
    }

    private fun stopForecast() {
        source?.stop()
        announcer?.release()
        cabin?.release()
        source = null
        announcer = null
        cabin = null
    }

    override fun onDestroy() {
        stopForecast()
        Log.i(TAG, "예보 멈춤")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START_NAVI = "com.sensoryforecast.action.START_NAVI"
        const val ACTION_START_DEMO = "com.sensoryforecast.action.START_DEMO"
        const val ACTION_STOP = "com.sensoryforecast.action.STOP"
        private const val TAG = "SensoryForecast"
        private const val CHANNEL = "forecast"
        private const val NOTIFICATION_ID = 1

        /** 아이 카드 화면이 생기기 전까지 쓰는 기본 카드입니다. */
        val DEFAULT_CARD = ChildCard(
            name = "아이",
            seat = SeatPosition.REAR_RIGHT,
            alertKinds = RoadEventKind.entries.toSet(),
            cueModes = CueMode.entries.toSet(),
            lightColorArgb = 0xFF00E5FF.toInt(),
        )

        fun intent(context: Context, action: String) =
            Intent(context, ForecastService::class.java).setAction(action)
    }
}
