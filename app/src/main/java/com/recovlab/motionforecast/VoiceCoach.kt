package com.recovlab.motionforecast

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import java.util.Locale
import kotlin.math.abs

/**
 * 주행 중 목소리 — 설계서 v1 '주행 중 목소리 규칙' 그대로.
 *  - 말하는 대상은 뒷자리 아이, 운전 코칭 안 함
 *  - 10분에 1번 이하, 문장 3초 이내
 *  - 말하지 않는 때: 방향지시등 켜짐 · 조향각 30° 이상 · 브레이크 눌림 · 최근 3초 |a| > 1.5 m/s² · 회전 안내 300 m 이내
 *  - 운전자에게는 1시간 30분 연속 주행 시 한 번만 휴식 제안
 *  - 뒷좌석이 비어 있으면 목소리 끔
 * Pleos TTS 온디바이스 · Locale.KOREA · initialize() 성공 뒤 리스너 (시험에서 확인한 순서)
 */
class VoiceCoach(context: Context, private val log: (String) -> Unit) {

    data class State(
        val tSec: Double,
        val rearOccupied: Boolean,
        val turnOn: Boolean,
        val steerDeg: Double?,
        val brake: Boolean,
        val recentMaxAbsAx: Double,   // last 3 s
        val nextTurnM: Double?,       // NaviHelper TBT distance
        val recentShakeRms: Double,
        val drivingSec: Double,
    )

    var minIntervalSec = 600.0           // 10분에 1번 이하 (시연 모드에서 줄임)
    var shakeTriggerRms = ShakeEngine.LEVEL_LIMITS[1]   // '울렁' 이상이면 아이에게 한 마디
    var restAfterSec = 90 * 60.0

    var spokenCount = 0; private set
    var suppressedCount = 0; private set
    val history = ArrayList<String>()

    private var lastSpokeSec = -1e9
    private var restSuggested = false
    private var ready = false
    private var lastSuppressReason = ""

    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)

    init {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                ready = true
                tts.addEventListener(object : EventListener {
                    override fun onReady() {}
                    override fun onStart() { log("voice onStart") }
                    override fun onDone() { log("voice onDone") }
                    override fun onStop() {}
                    override fun onError(errorType: ErrorType) { log("voice onError $errorType") }
                    override fun onUpdatedRms(rms: Double) {}
                })
                tts.setLocale(Locale.KOREA)
                log("voice ready (Pleos TTS ON_DEVICE, ko-KR)")
            }
            override fun onFailure(errorType: ErrorType) { log("voice init failed $errorType") }
        })
    }

    /** Returns null when nothing to say; otherwise speaks (or records why it stayed quiet). */
    fun consider(s: State): String? {
        if (!s.rearOccupied) return null                         // 뒷좌석 비면 목소리 끔
        val wantRest = !restSuggested && s.drivingSec >= restAfterSec
        val wantChild = s.recentShakeRms >= shakeTriggerRms && s.tSec - lastSpokeSec >= minIntervalSec
        if (!wantRest && !wantChild) return null

        val quiet = quietReason(s)
        if (quiet != null) {
            if (quiet != lastSuppressReason) { suppressedCount++; log("voice 참음: $quiet"); lastSuppressReason = quiet }
            return null
        }
        lastSuppressReason = ""
        val line = if (wantRest) { restSuggested = true; "다음 휴게소에서 쉬어 가면 뒷자리 날씨가 맑아져요." }
                   else "창밖 먼 산을 봐요."
        speak(line, s.tSec)
        return line
    }

    fun quietReason(s: State): String? = when {
        s.turnOn -> "방향지시등 켜짐"
        s.steerDeg != null && abs(s.steerDeg) >= 30.0 -> "조향각 ${abs(s.steerDeg).toInt()}°"
        s.brake -> "브레이크 눌림"
        s.recentMaxAbsAx > 1.5 -> "속도 변화 큼 (최근 3초 ${"%.1f".format(s.recentMaxAbsAx)} m/s²)"
        s.nextTurnM != null && s.nextTurnM <= 300.0 -> "회전 안내 ${s.nextTurnM.toInt()} m 앞"
        else -> null
    }

    private fun speak(line: String, tSec: Double) {
        lastSpokeSec = tSec
        spokenCount++
        history.add(line)
        log("voice speak: $line")
        if (ready) try { tts.speak(line, true) } catch (e: Throwable) { log("voice speak failed $e") }
        else log("voice not ready — 말하지 못함")
    }

    fun reset() { lastSpokeSec = -1e9; restSuggested = false; spokenCount = 0; suppressedCount = 0; history.clear(); lastSuppressReason = "" }

    fun release() { try { tts.release() } catch (_: Throwable) {} }
}
