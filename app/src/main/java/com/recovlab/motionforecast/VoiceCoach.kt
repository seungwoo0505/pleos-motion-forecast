package com.recovlab.motionforecast

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs

/**
 * 주행 중 목소리 — 기획서 v2 3장 '음성 사양' + 설계서 '말하지 않는 때'.
 *  - 켜고 끄기: 운전자가 출발 전 카드에서 (기본 꺼짐)
 *  - 문장 2초 이내 목표, 10분에 1번 이하, 한 주행 최대 6번(가정값)
 *  - 차 안 모두에게 들리는 안내 — 특정 좌석·연령에게 지시하지 않음, '멀미'라는 말 쓰지 않음
 *  - 말하지 않는 때: 방향지시등 · 조향 30° 이상 · 브레이크 · 최근 3초 |a| > 1.5 · 회전 안내 300 m 이내
 *  - 운전자 휴식 제안: 1시간 30분 연속 주행 시 1번만
 *  - 동승자(동승석·2열)가 없으면 조용
 *  - 음악 재생 중이면 잠깐 낮춤: 내비 안내 용도로 오디오 포커스(GAIN_TRANSIENT_MAY_DUCK) 요청
 * Pleos TTS 온디바이스 · Locale.KOREA · initialize() 성공 뒤 리스너
 */
class VoiceCoach(context: Context, private val dryRun: Boolean = false, private val log: (String) -> Unit) {

    data class State(
        val tSec: Double,
        val passengerPresent: Boolean,
        val turnOn: Boolean,
        val steerDeg: Double?,
        val brake: Boolean,
        val recentMaxAbsAx: Double,   // last 3 s
        val nextTurnM: Double?,       // NaviHelper TBT distance to the next maneuver
        val curvesAhead: Boolean,     // NaviHelper TBT: 2+ maneuvers within 300 m~2 km
        val recentShakeRms: Double,
        val drivingSec: Double,
        val speedKmh: Double,
    )

    companion object {
        // v3.1: 2초 안에 맞춤 — 에뮬레이터 녹음으로 잰 말 길이 1.78초 (기획서 v2 문장 "곧 굽은 길이에요. 먼 곳을 함께 봐요."는 2.46초)
        const val LINE_CURVE = "굽은 길, 먼 곳을 함께 봐요."
        const val LINE_REST = "다음 휴게소에서 쉬어 가요."
        const val MAX_PER_TRIP = 6
    }

    var enabled = false                    // 기본 꺼짐 — 출발 전 카드 토글
    var minIntervalSec = 600.0             // 10분에 1번 이하 (시연 모드에서 줄임)
    var shakeTriggerRms = ShakeEngine.LEVEL_LIMITS[1]
    var restAfterSec = 90 * 60.0

    var spokenCount = 0; private set
    var suppressedCount = 0; private set
    val history = ArrayList<String>()
    var lastSpeakMs = 0L; private set      // onStart → onDone (말한 길이 확인용)

    private var lastSpokeSec = -1e9
    private var restSuggested = false
    private var ready = false
    private var lastSuppressReason = ""
    private var startedAt = 0L

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()

    private val tts: TextToSpeech? = if (dryRun) null else TextToSpeech(context.applicationContext, Mode.ON_DEVICE)

    init {
        tts?.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                ready = true
                tts.addEventListener(object : EventListener {
                    override fun onReady() {}
                    override fun onStart() { startedAt = SystemClock.elapsedRealtime(); log("voice onStart") }
                    override fun onDone() {
                        lastSpeakMs = SystemClock.elapsedRealtime() - startedAt
                        log("voice onDone (${lastSpeakMs} ms)"); releaseFocus()
                    }
                    override fun onStop() { releaseFocus() }
                    override fun onError(errorType: ErrorType) { log("voice onError $errorType"); releaseFocus() }
                    override fun onUpdatedRms(rms: Double) {}
                })
                tts.setLocale(Locale.KOREA)
                try { tts.setAudioAttributes(attrs) } catch (e: Throwable) { log("voice setAudioAttributes failed $e") }
                log("voice ready (Pleos TTS ON_DEVICE, ko-KR, 내비 안내 용도)")
            }
            override fun onFailure(errorType: ErrorType) { log("voice init failed $errorType") }
        })
    }

    /** Returns the line spoken (or that would be spoken in dryRun), null otherwise. */
    fun consider(s: State): String? {
        if (!enabled) return null
        if (!s.passengerPresent) return null                       // 동승자 없으면 조용
        if (spokenCount >= MAX_PER_TRIP) return null               // 한 주행 최대 6번
        if (s.speedKmh < 10.0) return null                         // 달리는 중에만 (정지·출발 직전에는 말하지 않음)
        val wantRest = !restSuggested && s.drivingSec >= restAfterSec
        val wantCurve = (s.curvesAhead || s.recentShakeRms >= shakeTriggerRms) && s.tSec - lastSpokeSec >= minIntervalSec
        if (!wantRest && !wantCurve) return null

        val quiet = quietReason(s)
        if (quiet != null) {
            if (quiet != lastSuppressReason) { suppressedCount++; log("voice 참음: $quiet"); lastSuppressReason = quiet }
            return null
        }
        lastSuppressReason = ""
        val line = if (wantRest) { restSuggested = true; LINE_REST } else LINE_CURVE
        val why = if (wantRest) "1시간 30분 연속 주행" else if (s.curvesAhead) "회전 안내상 굽은 길 이어짐" else "최근 흔들림 '${Forecast.NAMES[ShakeEngine.level(s.recentShakeRms)]}'"
        speak(line, s.tSec, why)
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

    private fun speak(line: String, tSec: Double, why: String) {
        lastSpokeSec = tSec
        spokenCount++
        history.add(line)
        log("voice speak: $line  (이유: $why, ${spokenCount}/$MAX_PER_TRIP)")
        if (dryRun) return
        val g = try { audio.requestAudioFocus(focus) } catch (e: Throwable) { -1 }
        log("voice audio focus ${when (g) { AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "GRANTED(음악은 잠깐 낮춤)"; AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "DELAYED"; else -> "FAILED($g)" }}")
        if (ready) try { tts?.speak(line, true) } catch (e: Throwable) { log("voice speak failed $e"); releaseFocus() }
        else { log("voice not ready — 말하지 못함"); releaseFocus() }
    }

    /** 개발용: 규칙 없이 바로 말하기 (문장 길이 재기). 횟수에 넣지 않음 */
    fun speakTest(line: String) {
        log("voice test: $line")
        if (!ready) { log("voice not ready"); return }
        try { audio.requestAudioFocus(focus); tts?.speak(line, true) } catch (e: Throwable) { log("voice test failed $e"); releaseFocus() }
    }

    private fun releaseFocus() { try { audio.abandonAudioFocusRequest(focus) } catch (_: Throwable) {} }

    fun reset() { lastSpokeSec = -1e9; restSuggested = false; spokenCount = 0; suppressedCount = 0; history.clear(); lastSuppressReason = "" }

    fun release() { releaseFocus(); try { tts?.release() } catch (_: Throwable) {} }
}
