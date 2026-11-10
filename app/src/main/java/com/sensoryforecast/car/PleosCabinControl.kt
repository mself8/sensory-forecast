package com.sensoryforecast.car

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import com.sensoryforecast.core.CabinControl
import com.sensoryforecast.core.SeatPosition

/**
 * Pleos Vehicle SDK로 아이 자리 창문, 공조, 조명을 바꿉니다. 실패는 기록만 하고 넘어갑니다.
 * 공조는 차마다 받는 영역이 달라, 바꾸기 전에 차가 지원하는 영역을 먼저 묻습니다.
 */
class PleosCabinControl(context: Context) : CabinControl {

    private val vehicle = Vehicle(context.applicationContext).also { it.initialize() }

    override fun closeWindow(seat: SeatPosition) {
        Log.i(TAG, "창문 닫기: $seat")
        vehicle.getWindow().setWindowPosition(
            WindowArea(windowOf(seat)), WINDOW_CLOSED,
            { area, value -> Log.i(TAG, "창문 결과: ${area.values.toList()} = $value") },
            ::failed,
        )
    }

    override fun setRecirculation(on: Boolean) {
        Log.i(TAG, "내기 순환: $on")
        val hvac = vehicle.getHVAC()
        hvac.checkHvacRecirculationCapabilities({ _, areas ->
            val area = areas.firstOrNull()
            if (area == null) {
                Log.w(TAG, "내기 순환 지원 영역 없음")
                return@checkHvacRecirculationCapabilities
            }
            hvac.setHvacRecirculation(area, on, { _, value -> Log.i(TAG, "내기 결과: $value") }, ::failed)
        }, ::failed)
    }

    override fun lowerFanTo(level: Int) {
        val hvac = vehicle.getHVAC()
        hvac.checkHvacFanSpeedCapabilities({ _, bounds ->
            val area = bounds.keys.firstOrNull()
            if (area == null) {
                Log.w(TAG, "바람 지원 영역 없음")
                return@checkHvacFanSpeedCapabilities
            }
            hvac.getHvacFanSpeed(area, { current ->
                Log.i(TAG, "바람: 지금 $current, 목표 $level 이하")
                if (current != null && current > level) {
                    hvac.setHvacFanSpeed(area, level, { _, value -> Log.i(TAG, "바람 결과: $value") }, ::failed)
                }
            }, ::failed)
        }, ::failed)
    }

    /** 아이 자리 조명이 따로 없으면 차가 지원하는 첫 영역, 보통 차 전체 조명을 바꿉니다. */
    override fun setSeatLight(seat: SeatPosition, colorArgb: Int) {
        Log.i(TAG, "조명: $seat ${Integer.toHexString(colorArgb)}")
        val light = vehicle.getLight()
        light.checkAmbientLightBrightnessColorCapabilities({ _, areas ->
            val wanted = seatOf(seat)
            val area = areas.firstOrNull { wanted in it.values } ?: areas.firstOrNull()
            if (area == null) {
                Log.w(TAG, "조명 지원 영역 없음")
                return@checkAmbientLightBrightnessColorCapabilities
            }
            light.setAmbientLightBrightnessColor(
                area, colorArgb,
                { a, value -> Log.i(TAG, "조명 결과: ${a.values.toList()} ${Integer.toHexString(value)}") },
                ::failed,
            )
        }, ::failed)
    }

    fun release() = vehicle.release()

    private fun failed(e: Exception) = Log.w(TAG, "차 제어 실패", e)

    private fun windowOf(seat: SeatPosition) = when (seat) {
        SeatPosition.FRONT_RIGHT -> WindowValue.ROW_1_RIGHT
        SeatPosition.REAR_LEFT -> WindowValue.ROW_2_LEFT
        SeatPosition.REAR_RIGHT -> WindowValue.ROW_2_RIGHT
    }

    private fun seatOf(seat: SeatPosition) = when (seat) {
        SeatPosition.FRONT_RIGHT -> SeatValue.ROW_1_RIGHT
        SeatPosition.REAR_LEFT -> SeatValue.ROW_2_LEFT
        SeatPosition.REAR_RIGHT -> SeatValue.ROW_2_RIGHT
    }

    companion object {
        private const val TAG = "SensoryForecast"

        /** 창문 위치 0을 닫힘으로 봅니다. 에뮬레이터에서 50으로 연 창문이 0으로 닫히는 것을 확인했습니다. */
        private const val WINDOW_CLOSED = 0
    }
}
