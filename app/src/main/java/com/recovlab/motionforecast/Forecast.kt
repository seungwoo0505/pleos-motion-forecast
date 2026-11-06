package com.recovlab.motionforecast

import java.util.Locale

/** 출발 전 날씨 — 설계서 v1 2장 '출발 전 날씨'. 사실 기반, 확률 표시 안 함. 가정값. */
data class Forecast(
    val destination: String,
    val distanceKm: Double,
    val turns: Int,
    val congestionKm: Double,
    val stopGoSections: Int,
    val durationSec: Int,
) {
    /** 혼잡 비율 = 혼잡 km / 경로 km (0~1) */
    val congestionRatio: Double get() = if (distanceKm > 0) (congestionKm / distanceKm).coerceIn(0.0, 1.0) else 0.0

    /**
     * v1.1 (2026-11-06): 혼잡 항을 'km × 0.5'에서 '비율 × 3.0'으로 바꿈 — 긴 경로일수록 혼잡 km가 커져 거의 '폭풍'만 나오던 문제.
     * 비율 1.0(전 구간 혼잡)이면 3점 = 그것만으로 '울렁' 경계. 가중치 3.0은 가정값.
     */
    val score: Double
        get() {
            val turnsPerKm = if (distanceKm > 0) turns / distanceKm else 0.0
            return turnsPerKm * 1.0 + congestionRatio * CONGESTION_WEIGHT + durationSec / 3600.0 * 1.0
        }

    /** 0 맑음 · 1 구름 · 2 울렁 · 3 폭풍 — 맑음 < 1.5 ≤ 구름 < 3 ≤ 울렁 < 4.5 ≤ 폭풍 */
    val level: Int get() = when { score < 1.5 -> 0; score < 3.0 -> 1; score < 4.5 -> 2; else -> 3 }

    fun facts(): String {
        val h = durationSec / 3600; val m = (durationSec % 3600) / 60
        val time = if (h > 0) "${h}시간 ${m}분" else "${m}분"
        return "회전 ${turns}번 · 가다 서다 ${stopGoSections}구간 · $time"
    }

    fun detail(): String = String.format(Locale.US, "%s %.1f km · 혼잡 %.1f km (%.0f%%) · 점수 %.2f", destination, distanceKm, congestionKm, congestionRatio * 100, score)

    companion object {
        const val CONGESTION_WEIGHT = 3.0
        val NAMES = arrayOf("맑음", "구름", "울렁", "폭풍")

        /** 준비 3가지(창가 · 간식 · 쉬는 곳) — 단계별 문구 */
        fun prep(level: Int): List<String> = when (level) {
            0 -> listOf("창가 자리에 앉아요", "물 한 병이면 충분해요", "쉬지 않고 가도 괜찮아요")
            1 -> listOf("창가 자리, 먼 곳 보기", "가벼운 간식 (크래커)", "1시간쯤에 한 번 쉬어요")
            2 -> listOf("창가 자리 + 창문 살짝", "가벼운 간식, 단 음료는 빼요", "40분마다 쉬는 곳 들러요")
            else -> listOf("앞쪽 창가 자리 권해요", "빈속은 피하고 조금만 먹어요", "30분마다 쉬어 가요")
        }
    }
}
