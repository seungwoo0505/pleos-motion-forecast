package com.recovlab.motionforecast

import java.util.Locale

/** 탑승자 유형 — 사람이 고름 (2열 착석 = 아이로 가정하지 않음, 기획서 v2 음성 사양) */
enum class Passenger(val label: String) { CHILD("아이"), ADULT("어른"), SENIOR("어르신") }

/**
 * 출발 전 흔들림 예보 — 사실 기반, 확률 표시 안 함. 가정값.
 * v1.1: 혼잡 항 = 혼잡 비율 × 3.0 / v3: 단계 이름 '폭풍' → '출렁'(기획서 v2 3장)
 */
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

    val score: Double
        get() {
            val turnsPerKm = if (distanceKm > 0) turns / distanceKm else 0.0
            return turnsPerKm * 1.0 + congestionRatio * CONGESTION_WEIGHT + durationSec / 3600.0 * 1.0
        }

    /** 0 맑음 · 1 구름 · 2 울렁 · 3 출렁 — 맑음 < 1.5 ≤ 구름 < 3 ≤ 울렁 < 4.5 ≤ 출렁 */
    val level: Int get() = when { score < 1.5 -> 0; score < 3.0 -> 1; score < 4.5 -> 2; else -> 3 }

    fun timeText(): String {
        val h = durationSec / 3600; val m = (durationSec % 3600) / 60
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }

    /** 기획서 0장: "굽은 길 14번, 가다 서다 2구간, 1시간 10분" */
    fun facts(): String = "굽은 길 ${turns}번 · 가다 서다 ${stopGoSections}구간 · ${timeText()}"

    fun detail(): String = String.format(Locale.US, "%s %.1f km · 혼잡 %.1f km (%.0f%%) · 점수 %.2f", destination, distanceKm, congestionKm, congestionRatio * 100, score)

    companion object {
        const val CONGESTION_WEIGHT = 3.0
        val NAMES = arrayOf("맑음", "구름", "울렁", "출렁")

        /** 캐릭터 말투(가칭 '구름이') — 출발 전 한 줄 */
        val PLAN_LINE = arrayOf(
            "오늘은 맑음! 편하게 다녀와요.",
            "오늘은 구름 조금! 창가 자리가 편할 거예요.",
            "오늘은 울렁! 창문 살짝, 쉬는 곳도 챙겨요.",
            "오늘은 출렁! 쉬어 가는 길로 가요.",
        )

        /** 예보 카드의 큰 글자 아래 한 줄 — "오늘은 울렁!"은 큰 글자가 말하므로 뒷부분만 */
        val PLAN_SAY = PLAN_LINE.map { it.substringAfter("! ") }

        /** 캐릭터 말투 — 도착 후 한 줄 */
        val ARRIVE_LINE = arrayOf(
            "도착! 오늘도 다 같이 잘 왔어요.",
            "도착! 오늘도 다 같이 잘 왔어요.",
            "도착! 오늘도 다 같이 잘 왔어요.",
            "도착! 오늘도 다 같이 잘 왔어요.",
        )

        /** 준비 3가지(자리 · 간식 · 쉬는 곳) — 탑승자 유형별, 유형을 안 골랐으면 기본 */
        fun prep(level: Int, who: Passenger?): List<String> = when (who) {
            Passenger.CHILD -> when (level) {
                0 -> listOf("창가 자리에 앉혀요", "물 한 병이면 충분해요", "쉬지 않고 가도 괜찮아요")
                1 -> listOf("창가 자리, 먼 곳 보기 놀이", "가벼운 간식 (크래커)", "1시간쯤에 한 번 쉬어요")
                2 -> listOf("창가 자리 + 창문 살짝", "가벼운 간식 챙기기", "40분마다 쉬는 곳 들러요")
                else -> listOf("앞쪽 창가 자리 권해요", "빈속은 피하고 조금만 먹어요", "30분마다 쉬어 가요")
            }
            Passenger.ADULT -> when (level) {
                0 -> listOf("편한 자리에서 쉬어요", "물 한 병이면 충분해요", "쉬지 않고 가도 괜찮아요")
                1 -> listOf("창가 자리, 휴대폰은 잠깐 내려놓기", "가벼운 간식", "1시간쯤에 한 번 쉬어요")
                2 -> listOf("앞자리나 창가 자리 + 창문 살짝", "가벼운 간식 챙기기", "40분마다 쉬는 곳 들러요")
                else -> listOf("동승석 권해요", "빈속은 피하고 조금만 먹어요", "30분마다 쉬어 가요")
            }
            Passenger.SENIOR -> when (level) {
                0 -> listOf("등받이를 편하게 맞춰요", "물 한 병 챙기기", "1시간쯤에 한 번 쉬어요")
                1 -> listOf("등받이 · 목받침 맞추기", "물과 가벼운 간식", "50분마다 쉬어요")
                2 -> listOf("앞자리 권해요 + 창문 살짝", "가벼운 간식 챙기기", "30분마다 쉬는 곳 들러요")
                else -> listOf("앞자리 권해요", "빈속은 피하고 조금만 먹어요", "30분마다, 쉬는 곳 위치도 미리")
            }
            null -> when (level) {
                0 -> listOf("창가 자리에 앉아요", "물 한 병이면 충분해요", "쉬지 않고 가도 괜찮아요")
                1 -> listOf("창가 자리, 먼 곳 보기", "가벼운 간식 (크래커)", "1시간쯤에 한 번 쉬어요")
                2 -> listOf("창가 자리 + 창문 살짝", "가벼운 간식 챙기기", "40분마다 쉬는 곳 들러요")
                else -> listOf("앞쪽 창가 자리 권해요", "빈속은 피하고 조금만 먹어요", "30분마다 쉬어 가요")
            }
        }
    }
}
