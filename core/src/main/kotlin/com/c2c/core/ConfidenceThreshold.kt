package com.c2c.core

import kotlin.math.roundToInt

/**
 * 자동 등록 기준 확신도. Claude가 매긴 확신도가 이 값 이상인 일정만 캘린더에 등록한다.
 * 내부 값은 0.1~1.0, 0.05 단위. 화면에는 10%~100%로 보여 준다.
 */
object ConfidenceThreshold {
    const val MIN = 0.1
    const val MAX = 1.0
    const val STEP = 0.05
    const val DEFAULT = EventFilter.DEFAULT_MIN_CONFIDENCE

    /** 슬라이더의 중간 눈금 수 (양 끝 제외): (1.0 - 0.1) / 0.05 - 1 = 17 */
    val SLIDER_STEPS: Int = ((MAX - MIN) / STEP).roundToInt() - 1

    /** 범위 안으로 자르고 0.05 단위로 맞춘다 (부동소수점 오차 없이 소수 둘째 자리로). */
    fun snap(value: Double): Double {
        if (value.isNaN()) return DEFAULT
        val steps = (value.coerceIn(MIN, MAX) / STEP).roundToInt()
        return (steps * 5) / 100.0
    }

    /** 0.6 → 60 */
    fun toPercent(value: Double): Int = (snap(value) * 100).roundToInt()

    /** 60 → 0.6 */
    fun fromPercent(percent: Int): Double = snap(percent / 100.0)
}
