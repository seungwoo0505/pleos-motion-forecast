package com.recovlab.motionforecast

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 흔들림 계산 — 설계서 v1 2장 그대로. 모든 상수는 가정값.
 *  a_x = dv/dt (속도 1초 이동 평균 후 미분)
 *  a_y ≈ v² · tan(δ_sw / i) / L   (i = 14.5, L = 2.9 m) — 조향각이 없을 때는 v · Δheading/Δt
 *  흔들림 지수 = 0.1~0.5 Hz 성분을 제곱해 시간으로 쌓은 값의 제곱근 (MSDV 개념을 참고한 자체 지수, 의학적 판정 아님)
 */
class ShakeEngine(val dtSec: Double = 0.1) {

    data class Sample(
        val tSec: Double,          // seconds since drive start
        val speedKmh: Double,
        val steerDeg: Double?,     // corrected sign
        val lat: Double?, val lon: Double?,
        val ax: Double, val ay: Double,
        val shake: Double,         // band-passed horizontal magnitude, m/s²
        val turnOn: Boolean, val brake: Boolean,
    )

    data class Event(val tSec: Double, val kind: String, val value: Double, val lat: Double?, val lon: Double?)

    companion object {
        const val STEER_RATIO = 14.5        // 조향비 i (가정값)
        const val WHEELBASE_M = 2.9         // 축거 L (가정값)
        const val HARD_BRAKE = -3.0         // 급제동 기준 m/s² (가정값) — 감점이 아니라 기록
        const val STOP_KMH = 10.0           // 정체 반복: 10 km/h 아래 ↔ 30 km/h 위
        const val GO_KMH = 30.0
        // 흔들림 단계 (구간 RMS, m/s²) — 가정값, 시연 뒤 조정
        val LEVEL_LIMITS = doubleArrayOf(0.15, 0.30, 0.50)
        fun level(rms: Double): Int = LEVEL_LIMITS.indexOfFirst { rms < it }.let { if (it < 0) 3 else it }
    }

    val samples = ArrayList<Sample>()
    val events = ArrayList<Event>()

    private val speedWin = ArrayDeque<Double>()     // m/s, 1 s window
    private var prevAvg: Double? = null
    private val hpX = HighPass(0.1, dtSec); private val lpX = LowPass(0.5, dtSec)
    private val hpY = HighPass(0.1, dtSec); private val lpY = LowPass(0.5, dtSec)
    var msdvSq = 0.0; private set

    // heading fallback
    private var lastFixLat: Double? = null; private var lastFixLon: Double? = null
    private var lastHeading: Double? = null; private var lastHeadingT = 0.0
    private var headingRate = 0.0

    // stop-and-go
    private var zone = 0   // -1 below 10, +1 above 30
    var stopGoSwitches = 0; private set
    private var lastBrakeEventT = -99.0

    fun step(tSec: Double, speedKmh: Double, steerDeg: Double?, lat: Double?, lon: Double?, turnOn: Boolean, brake: Boolean): Sample {
        val v = speedKmh / 3.6
        speedWin.addLast(v)
        val n = (1.0 / dtSec).toInt().coerceAtLeast(1)
        while (speedWin.size > n) speedWin.removeFirst()
        val avg = speedWin.average()
        val ax = prevAvg?.let { (avg - it) / dtSec } ?: 0.0
        prevAvg = avg

        updateHeading(tSec, lat, lon)
        val ay = if (steerDeg != null) {
            val roadWheel = Math.toRadians(steerDeg) / STEER_RATIO
            avg * avg * tan(roadWheel) / WHEELBASE_M
        } else avg * headingRate

        val fx = lpX.step(hpX.step(ax)); val fy = lpY.step(hpY.step(ay))
        val aw2 = fx * fx + fy * fy
        msdvSq += aw2 * dtSec
        val s = Sample(tSec, speedKmh, steerDeg, lat, lon, ax, ay, sqrt(aw2), turnOn, brake)
        samples.add(s)

        // 정체 반복
        val z = when { speedKmh < STOP_KMH -> -1; speedKmh > GO_KMH -> 1; else -> zone }
        if (zone != 0 && z != zone) stopGoSwitches++
        zone = z
        // 급제동 (기록만)
        if (ax < HARD_BRAKE && tSec - lastBrakeEventT > 3.0) { events.add(Event(tSec, "급제동", ax, lat, lon)); lastBrakeEventT = tSec }
        return s
    }

    private fun updateHeading(t: Double, lat: Double?, lon: Double?) {
        if (lat == null || lon == null) return
        val pLat = lastFixLat; val pLon = lastFixLon
        if (pLat != null && pLon != null && (pLat != lat || pLon != lon)) {
            val y = sin(Math.toRadians(lon - pLon)) * cos(Math.toRadians(lat))
            val x = cos(Math.toRadians(pLat)) * sin(Math.toRadians(lat)) - sin(Math.toRadians(pLat)) * cos(Math.toRadians(lat)) * cos(Math.toRadians(lon - pLon))
            val h = atan2(y, x)
            lastHeading?.let { prev ->
                var d = h - prev
                while (d > Math.PI) d -= 2 * Math.PI
                while (d < -Math.PI) d += 2 * Math.PI
                val dt = (t - lastHeadingT).coerceAtLeast(0.2)
                headingRate = d / dt
            }
            lastHeading = h; lastHeadingT = t
        }
        lastFixLat = lat; lastFixLon = lon
    }

    /** 흔들림 지수 (자체 지수, m/s^1.5) */
    fun index(): Double = sqrt(msdvSq)

    fun durationSec(): Double = samples.lastOrNull()?.tSec ?: 0.0

    /** RMS of the band-passed magnitude over the last [sec] seconds. */
    fun recentRms(sec: Double): Double {
        val n = (sec / dtSec).toInt()
        val tail = samples.takeLast(n)
        if (tail.isEmpty()) return 0.0
        return sqrt(tail.sumOf { it.shake * it.shake } / tail.size)
    }

    /** max |a_x| over the last [sec] seconds (말하지 않는 조건: 속도 변화 큼) */
    fun recentMaxAbsAx(sec: Double): Double = samples.takeLast((sec / dtSec).toInt()).maxOfOrNull { abs(it.ax) } ?: 0.0

    data class Window(val startSec: Double, val endSec: Double, val rms: Double, val lateral: Boolean, val lat: Double?, val lon: Double?)

    /** Fixed windows of [winSec] seconds → RMS, used for the graph and the top-3 list. */
    fun windows(winSec: Double): List<Window> {
        if (samples.isEmpty()) return emptyList()
        val per = (winSec / dtSec).toInt().coerceAtLeast(1)
        return samples.chunked(per).map { c ->
            val rms = sqrt(c.sumOf { it.shake * it.shake } / c.size)
            val lat = c.mapNotNull { it.lat }.lastOrNull(); val lon = c.mapNotNull { it.lon }.lastOrNull()
            val sy = c.sumOf { it.ay * it.ay }; val sx = c.sumOf { it.ax * it.ax }
            Window(c.first().tSec, c.last().tSec + dtSec, rms, sy > sx, lat, lon)
        }
    }

    fun stopGoPer10Min(): Double {
        val min = durationSec() / 60.0
        return if (min <= 0) 0.0 else stopGoSwitches / max(min / 10.0, 1e-9)
    }

    // first-order filters (bilinear-free, simple RC form)
    class HighPass(fc: Double, dt: Double) {
        private val a = 1.0 / (1.0 + dt * 2 * Math.PI * fc); private var px = 0.0; private var py = 0.0; private var first = true
        fun step(x: Double): Double { if (first) { px = x; first = false }; val y = a * (py + x - px); px = x; py = y; return y }
    }
    class LowPass(fc: Double, dt: Double) {
        private val a = 1.0 - exp(-dt * 2 * Math.PI * fc); private var y = 0.0
        fun step(x: Double): Double { y += a * (x - y); return y }
    }
}
