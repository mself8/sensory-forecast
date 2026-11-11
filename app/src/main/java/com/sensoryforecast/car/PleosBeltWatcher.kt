package com.sensoryforecast.car

import android.content.Context
import android.os.Handler
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import com.sensoryforecast.core.SeatPosition

/**
 * 아이 자리 벨트가 매였는지를 듣습니다. 차 SDK는 바인더 스레드에서 부르므로 [handler]의 스레드로 넘깁니다.
 * 가상 콘솔에는 좌석별 벨트 신호가 없어, 실제 차에서 확인합니다.
 */
class PleosBeltWatcher(context: Context, private val handler: Handler, seat: SeatPosition) {

    private val vehicle = Vehicle(context.applicationContext)
    private val area = SeatArea(
        when (seat) {
            SeatPosition.FRONT_RIGHT -> SeatValue.ROW_1_RIGHT
            SeatPosition.REAR_LEFT -> SeatValue.ROW_2_LEFT
            SeatPosition.REAR_RIGHT -> SeatValue.ROW_2_RIGHT
        }
    )
    private var onChange: ((Boolean) -> Unit)? = null

    private val listener = object : SeatBeltBuckledStateListener {
        override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) {
            if (buckled != null) handler.post { onChange?.invoke(buckled) }
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "벨트 신호 실패", e)
        }
    }

    fun start(onChange: (Boolean) -> Unit) {
        this.onChange = onChange
        vehicle.initialize()
        vehicle.getSeat().registerSeatBeltBuckledState(area, listener)
    }

    fun stop() {
        onChange = null
        vehicle.getSeat().unregisterSeatBeltBuckledState(area, listener)
        vehicle.release()
    }

    private companion object {
        const val TAG = "SensoryForecast"
    }
}
