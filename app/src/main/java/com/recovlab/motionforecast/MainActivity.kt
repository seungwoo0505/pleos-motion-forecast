package com.recovlab.motionforecast

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.TrafficCongestionType
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 「승차감·흔들림 예보」 v4 — 기획서 v2 3장 · 부록 C 기준, 겉모습은 Screens.kt (물결 예보 카드 + 색 칸 일기).
 * 화면은 P에서만(출발 전 흔들림 예보 카드 · 도착 후 승차감 일기), 주행 중은 목소리만.
 * Log tag: MotionForecast
 */
class MainActivity : Activity() {

    private enum class Phase { PLAN, DRIVE, ARRIVING, DIARY }

    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    // ---- settings (보정 스위치 기본 켬 · 시연 모드 기본 꺼짐) ----
    private var invertSeat = true
    private var invertSteer = true
    private var demoMode = false
    private val arrivalWaitSec get() = if (demoMode) 5.0 else 30.0

    // ---- 출발 전 카드: 탑승자 유형 · 목소리 안내 ----
    private var passenger: Passenger? = null
    private var askedVoice = false

    // ---- 처음 한 번 보이는 한 장 요약 ([시작하기]를 누르면 다시 나오지 않음) ----
    private val prefs by lazy { getSharedPreferences("motionforecast", MODE_PRIVATE) }
    private var introOpen = false

    // ---- live vehicle values ----
    @Volatile private var speedKmh = 0.0
    @Volatile private var gear = VehicleGear.GEAR_PARK
    @Volatile private var steerRaw: Double? = null
    @Volatile private var turnOn = false
    @Volatile private var brake = false
    @Volatile private var lat: Double? = null
    @Volatile private var lon: Double? = null
    @Volatile private var nextTurnM: Double? = null
    private val seatRaw = linkedMapOf<String, Boolean?>("동승석" to null, "뒷줄 왼쪽" to null, "뒷줄 가운데" to null, "뒷줄 오른쪽" to null)
    private val doorOpen = linkedMapOf<String, Boolean>()

    private val steerDeg: Double? get() = steerRaw?.let { if (invertSteer) -it else it }
    private fun seatOccupied(k: String): Boolean? = seatRaw[k]?.let { if (invertSeat) !it else it }
    private val passengerPresent: Boolean get() = seatRaw.keys.any { seatOccupied(it) == true }

    // ---- trip ----
    private var phase = Phase.PLAN
    private var engine = ShakeEngine()
    private var driveStartMs = 0L
    private var lastStepMs = 0L      // 마지막으로 기록한 칸의 시각 (0.1초 간격을 지키는 데 씀)
    private var catchUps = 0         // 이번 주행에서 밀린 차례를 채운 횟수
    private var maxCatch = 1         // 한 번에 가장 많이 채운 칸 수
    private var parkSinceMs = 0L
    private var doorOpenedSincePark = false
    private var csv: File? = null
    private val tripLog = ArrayList<String>()
    private lateinit var voice: VoiceCoach

    // ---- route / forecast ----
    private var destName = ""
    private var routeStart: RouteStartInfo? = null
    private var tbt: List<TBTInfo> = emptyList()
    private var routeState: RouteStateInfo? = null
    private var forecast: Forecast? = null

    // ---- SDK handles ----
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var cpm: CarPropertyManager? = null
    private var fused: FusedLocation? = null
    private var navi: NaviHelper? = null

    // ---- views (그림은 Screens.kt) ----
    private lateinit var intro: IntroScene
    private lateinit var plan: PlanScene
    private lateinit var wait: WaitScene
    private lateinit var diary: DiaryScene
    private lateinit var settingsView: LinearLayout
    private lateinit var planStatus: TextView
    private lateinit var devDetail: TextView
    private var diaryText = ""   // 일기 요약 글 (기록 파일 · 로그용)

    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        voice = VoiceCoach(this) { log(it) }
        requestPermissions(arrayOf(Car.PERMISSION_SPEED, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, "pleos.car.permission.FUSED_LOCATION"), 1)
        startVehicle()
        startCar()
        startFused()
        startNavi()
        main.post(tick)
        introOpen = !prefs.getBoolean(INTRO_DONE, false)
        if (introOpen) log("intro 보임 (처음 실행)")
        show(Phase.PLAN)
        handleIntro(intent)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        log("permissions " + perms.zip(results.toTypedArray()).joinToString { "${it.first.substringAfterLast('.')}=${if (it.second == 0) "허용" else "거부"}" })
        car?.let { if (it.isConnected) setupCar(it) }
        registerFused()
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        if (phase == Phase.DRIVE || phase == Phase.ARRIVING) tripLog.add("${clock.format(Date())} $msg")
    }

    // =====================================================================
    // UI — 세 화면의 그림은 Screens.kt, 여기서는 값만 넘김. 설정(⋮)만 기본 위젯.
    private fun tv(size: Float, color: Int = Ui.INK, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); typeface = Ui.face(this@MainActivity, if (bold) 700 else 500); includeFontPadding = false
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.PAPER) }

        // ---------- ① 출발 전 (P) ----------
        plan = PlanScene(this).apply {
            destinations = DESTINATIONS.map { it.first }
            onPickDest = { i -> val (name, la, lo) = DESTINATIONS[i]; requestRoute(name, la, lo) }
            onPassenger = { pickPassenger(it) }
            onVoice = { setVoice(it, "토글") }
            onAskVoice = { yes -> if (yes) setVoice(true, "묻기: 켜기") else log("voice 묻기: 나중에") }
            onSettings = { openSettings(true) }
        }

        // ---------- 설정 (⋮) — 보정 · 시연 모드 · 개발용 정보 · 심사 캡처용 예시 ----------
        settingsView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(72, 30, 72, 32); setBackgroundColor(Ui.PAPER) }
        val sTitle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sTitle.addView(tv(40f, bold = true).apply {
            text = "←"; setPadding(0, 0, 28, 0)
            contentDescription = "돌아가기"; setOnClickListener { openSettings(false) }
        })
        sTitle.addView(tv(40f, bold = true).apply { text = "설정" })
        settingsView.addView(sTitle)
        fun section(t: String) = settingsView.addView(tv(24f, Ui.SUB).apply { text = t; setPadding(0, 40, 0, 10) })
        fun toggle(label: String, initial: Boolean, f: (Boolean) -> Unit) = settingsView.addView(CheckBox(this).apply {
            text = label; textSize = 28f; setTextColor(Ui.INK); typeface = Ui.face(this@MainActivity, 500)
            isChecked = initial; setPadding(12, 12, 0, 12); setOnCheckedChangeListener { _, c -> f(c) }
        })
        section("값 보정 (에뮬레이터에서 반대로 들어옴 · 실차 확인 필요)")
        toggle("착석 반대 보정", invertSeat) { invertSeat = it; log("setting invertSeat=$it") }
        toggle("조향 부호 보정", invertSteer) { invertSteer = it; log("setting invertSteer=$it") }
        section("시연")
        toggle("시연 모드 (도착 대기 5초 · 목소리 간격 15초 · 구간 3초)", demoMode) { setDemo(it) }
        section("예시")
        settingsView.addView(tv(28f, Color.WHITE, bold = true).apply {
            text = "예시 주행 40분의 승차감 일기 보기"; setPadding(48, 22, 48, 24)
            background = GradientDrawable().apply { cornerRadius = 60f; setColor(Ui.INK) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { showExampleTrip() }
        })
        section("개발용 정보")
        planStatus = tv(22f, Ui.SUB).apply { setPadding(8, 0, 0, 8); setLineSpacing(6f, 1f) }
        settingsView.addView(planStatus)
        devDetail = tv(22f, Ui.SUB).apply { setPadding(8, 0, 0, 0); setLineSpacing(6f, 1f) }
        settingsView.addView(devDetail)

        // ---------- ② 주행 중 (D) — 화면은 자동 차단, 목소리만 ----------
        wait = WaitScene(this)

        // ---------- ③ 도착 후 — 승차감 일기 ----------
        diary = DiaryScene(this).apply { onNewTrip = { show(Phase.PLAN) } }

        // ---------- 처음 한 번 — 한 장 요약 ----------
        intro = IntroScene(this).apply {
            onStart = { closeIntro("시작하기") }
            contentDescription = "처음 안내. 출발 전에는 가는 길 흔들림을 네 단계로 알려 드려요. 가는 동안에는 굽은 길 앞에서 목소리로 짧게 알려 드려요. 도착하면 어디서 흔들렸는지 일기로 보여 드려요. 시작하기를 누르세요."
        }

        for (v in listOf(plan, settingsView, wait, diary, intro)) root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    // ---- 탑승자 유형 · 목소리 안내 ----
    private fun pickPassenger(p: Passenger) {
        passenger = p
        log("passenger ${p.label}")
        plan.passenger = p
        // 처음 고를 때 한 번만 "목소리 안내를 켤까요?" (기획서 v2 음성 사양: 기본 꺼짐)
        if (!voice.enabled && !askedVoice) { askedVoice = true; plan.askVoice = true }
        plan.invalidate()
    }

    private fun closeIntro(why: String) {
        if (!introOpen) return
        introOpen = false
        prefs.edit().putBoolean(INTRO_DONE, true).apply()
        log("intro 닫음 ($why)")
        show(phase)
    }

    /**
     * 처음 안내를 건너뛰기 — am start -n …/.MainActivity --es intro skip  (시연 스크립트가 씀. 안내 화면만 넘기는 것이라 모든 빌드에서 됨)
     * 개발용 빌드에서만: 다시 보기 — --es intro show
     */
    private fun handleIntro(intent: Intent?) {
        when (intent?.getStringExtra("intro")) {
            "skip" -> closeIntro("intent")
            "show" -> if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) { introOpen = true; show(phase) }
        }
    }

    private fun setVoice(on: Boolean, why: String) { voice.enabled = on; plan.voiceOn = on; plan.invalidate(); log("voice enabled=$on ($why)") }

    private var settingsOpen = false
    private fun openSettings(open: Boolean) { settingsOpen = open; show(phase) }

    private fun setDemo(on: Boolean) {
        demoMode = on
        voice.minIntervalSec = if (on) 15.0 else 600.0
        log("setting demoMode=$on")
    }

    private fun show(p: Phase) {
        if (p != phase) settingsOpen = false
        phase = p
        val introNow = introOpen && p == Phase.PLAN   // 처음 안내는 출발 전 화면 위에만
        intro.visibility = if (introNow) View.VISIBLE else View.GONE
        plan.visibility = if (p == Phase.PLAN && !settingsOpen && !introNow) View.VISIBLE else View.GONE
        settingsView.visibility = if (p == Phase.PLAN && settingsOpen) View.VISIBLE else View.GONE
        wait.visibility = if (p == Phase.DRIVE || p == Phase.ARRIVING) View.VISIBLE else View.GONE
        wait.arriving = p == Phase.ARRIVING; wait.level = forecast?.level
        wait.animate = p != Phase.DRIVE   // 주행 중에는 차가 화면을 가림 — 물결을 멈춰 기록 주기를 지킴
        diary.visibility = if (p == Phase.DIARY) View.VISIBLE else View.GONE
        Log.i(TAG, "phase → $p")
    }

    private fun renderForecast() {
        val f = forecast ?: return
        plan.forecast = f; plan.busy = false; plan.error = null
        plan.contentDescription = "${f.destination} 가는 길, 오늘은 ${Forecast.NAMES[f.level]}. ${Forecast.PLAN_SAY[f.level]} ${f.facts()}"
        plan.invalidate()
        devDetail.text = "예보 계산: ${f.detail()} — 굽은 길/km + 혼잡 비율 × ${Forecast.CONGESTION_WEIGHT} + 시간(h), 기준 1.5 / 3 / 4.5 (가정값)"
        log("forecast ${f.detail()} → ${Forecast.NAMES[f.level]} (${f.facts()})")
    }

    private fun seatText(k: String) = when (seatOccupied(k)) { true -> "앉음"; false -> "비어 있음"; else -> "확인 중" }

    private fun renderStatus() {
        val seats = seatRaw.keys.joinToString(" · ") { "$it ${seatText(it)}" }
        val g = when (gear) { VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_DRIVE -> "D"; VehicleGear.GEAR_REVERSE -> "R"; VehicleGear.GEAR_NEUTRAL -> "N"; else -> "$gear" }
        val st = steerDeg?.let { "${"%.0f".format(it)}°" } ?: "확인 중"
        val pos = if (lat != null) "%.5f, %.5f".format(lat, lon) else "위치 확인 중"
        if (settingsView.visibility != View.VISIBLE) return
        planStatus.text = "좌석: $seats\n기어 $g · ${"%.0f".format(speedKmh)} km/h · 조향 $st · $pos · 목소리 안내 ${if (voice.enabled) "켬" else "끔"}" +
            (forecast?.let { " · 흔들림 지수(예보, 자체 기준) ${"%.2f".format(Locale.US, it.score)}" } ?: "")
    }

    // =====================================================================
    // trip loop (10 Hz)
    private var tickCount = 0
    private val tick = object : Runnable {
        override fun run() {
            tickCount++
            val now = SystemClock.elapsedRealtime()
            when (phase) {
                Phase.PLAN, Phase.DIARY -> if (gear == VehicleGear.GEAR_DRIVE) startTrip(now)
                Phase.DRIVE -> {
                    // 다른 처리에 밀려 이 차례가 늦게 와도 기록은 0.1초 간격을 지킨다 — 빠진 칸은 지금 값으로 채움.
                    // (채우지 않으면 '1초' 창이 실제보다 길어져 속도 변화를 크게 재고, 실제 단계가 높게 나온다. 11-14 확인)
                    val n = ((now - lastStepMs + 50) / 100).toInt().coerceIn(1, 20)
                    if (n > 1) { catchUps++; if (n > maxCatch) maxCatch = n }
                    repeat(n) { stepEngine(lastStepMs + 100L * (it + 1)) }
                    lastStepMs += 100L * n
                    if (tickCount % 5 == 0) considerVoice(now)
                    if (tickCount % 20 == 0) try { navi?.getCurrentLocationInfo() } catch (_: Throwable) {}   // 2초마다 내비 주소
                    if (tickCount % 30 == 0 && SystemClock.elapsedRealtime() - lastFixMs > 3000) { log("위치가 3초 넘게 안 옴 → FusedLocation 다시 등록"); registerFused() }
                    if (gear == VehicleGear.GEAR_PARK && speedKmh < 1.0) { parkSinceMs = now; doorOpenedSincePark = doorOpen.values.any { it }; show(Phase.ARRIVING); log("P — 도착 확인 시작") }
                }
                Phase.ARRIVING -> {
                    if (gear == VehicleGear.GEAR_DRIVE) { show(Phase.DRIVE); log("다시 D — 기록 계속") }
                    else if ((now - parkSinceMs) / 1000.0 >= arrivalWaitSec && doorOpenedSincePark) finishTrip()
                }
            }
            if (tickCount % 5 == 0) renderStatus()
            main.postDelayed(this, 100)
        }
    }

    private fun startTrip(now: Long) {
        engine = ShakeEngine()
        voice.reset()
        tripLog.clear()
        synchronized(addrFixes) { addrFixes.clear() }
        registerFused()   // 다시 설치한 직후 첫 등록이 서비스 연결 전에 빠지는 일이 있어 주행 시작 때 한 번 더
        driveStartMs = now; lastStepMs = now; catchUps = 0; maxCatch = 1
        show(Phase.DRIVE)
        val dir = File(filesDir, "trips").apply { mkdirs() }
        csv = File(dir, "trip_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv").also {
            it.writeText("t_sec,speed_kmh,steer_deg,lat,lon,ax,ay,shake,turn,brake,passenger\n")
        }
        log("D — 기록 시작 (목적지 ${destName.ifEmpty { "없음" }}, 예보 ${forecast?.let { Forecast.NAMES[it.level] } ?: "-"}, 탑승자 ${passenger?.label ?: "-"}, 동승자 ${if (passengerPresent) "있음" else "없음"}, 목소리 ${if (voice.enabled) "켬" else "끔"})")
    }

    private val csvBuf = StringBuilder()
    private fun stepEngine(now: Long) {
        val t = (now - driveStartMs) / 1000.0
        val s = engine.step(t, speedKmh, steerDeg, lat, lon, turnOn, brake)
        csvBuf.append("%.1f,%.1f,%s,%s,%s,%.3f,%.3f,%.3f,%d,%d,%d\n".format(Locale.US, s.tSec, s.speedKmh, s.steerDeg?.let { "%.1f".format(Locale.US, it) } ?: "",
            s.lat?.let { "%.6f".format(Locale.US, it) } ?: "", s.lon?.let { "%.6f".format(Locale.US, it) } ?: "", s.ax, s.ay, s.shake,
            if (s.turnOn) 1 else 0, if (s.brake) 1 else 0, if (passengerPresent) 1 else 0))
        if (csvBuf.length > 4000) flushCsv()
    }

    private fun flushCsv() { csv?.appendText(csvBuf.toString()); csvBuf.setLength(0) }

    /**
     * 회전 안내상 300 m~2 km 안에 회전이 2번 이상 → "곧 굽은 길".
     * TBT 거리는 '앞 회전 → 다음 회전' 간격으로 들어옴(예: 700 · 50 · 110 · 40 · 13000 m) → 누적해서 봄.
     */
    private fun curvesAhead(): Boolean {
        var cum = 0.0; var n = 0
        for (t in tbt) { cum += t.distance; if (cum > 2000.0) break; if (cum >= 300.0) n++ }
        return n >= 2
    }

    private fun considerVoice(now: Long) {
        val t = (now - driveStartMs) / 1000.0
        voice.consider(VoiceCoach.State(
            tSec = t, passengerPresent = passengerPresent, turnOn = turnOn, steerDeg = steerDeg, brake = brake,
            recentMaxAbsAx = engine.recentMaxAbsAx(3.0), nextTurnM = nextTurnM, curvesAhead = curvesAhead(),
            recentShakeRms = engine.recentRms(if (demoMode) 6.0 else 20.0), drivingSec = t, speedKmh = speedKmh,
        ))
    }

    private fun finishTrip() {
        flushCsv()
        renderDiary(engine, forecast, voice.spokenCount, voice.suppressedCount, example = false, topWindowSec = if (demoMode) 3.0 else 30.0)
        show(Phase.DIARY)
        log("도착 — 일기: " + diaryText.replace("\n", " / "))
        log("기록 주기: 밀린 차례를 ${catchUps}번 채움 (한 번에 가장 많이 ${maxCatch}칸)")
        val summary = File(csv?.parentFile, (csv?.nameWithoutExtension ?: "trip") + "_summary.txt")
        summary.writeText(diaryText + "\n\n목소리: ${voice.history} (마지막 말한 길이 ${voice.lastSpeakMs} ms)\n\n기록:\n" + tripLog.joinToString("\n") + "\n")
    }

    /** 승차감 일기 — 실제 주행과 예시 주행이 같은 화면을 씀 */
    private fun renderDiary(e: ShakeEngine, fc: Forecast?, spoken: Int, suppressed: Int, example: Boolean, topWindowSec: Double) {
        val dur = e.durationSec()
        val overall = sqrt(e.samples.sumOf { it.shake * it.shake } / e.samples.size.coerceAtLeast(1))
        val level = ShakeEngine.level(overall)
        val long = dur >= 180

        // 색 칸 한 칸의 길이 — 긴 주행은 분 단위(60칸 이하), 짧은 시연은 초 단위(40칸 이하)
        val tileSec = if (long) 60.0 * ceil(dur / 3600.0).coerceAtLeast(1.0) else listOf(1.0, 2.0, 5.0).firstOrNull { dur / it <= 40 } ?: 5.0
        val wins = e.windows(tileSec).filterIndexed { i, w -> i == 0 || w.endSec - w.startSec >= tileSec * 0.2 }   // 끝에 남은 자투리 칸은 버림
        val tiles = IntArray(wins.size) { ShakeEngine.level(wins[it].rms) }
        val span = (wins.size * tileSec).coerceAtLeast(1.0)
        val tickStep = if (long) listOf(300.0, 600.0, 900.0, 1200.0, 1800.0, 3600.0).firstOrNull { span / it <= 6 } ?: 7200.0
            else listOf(5.0, 10.0, 15.0, 30.0, 60.0).firstOrNull { span / it <= 6 } ?: 60.0
        val ticks = ArrayList<Pair<Float, String>>()
        var t = 0.0
        while (t <= span + 1e-6) { ticks.add((t / span).toFloat() to if (long) "${(t / 60).toInt()}분" else "${t.toInt()}초"); t += tickStep }
        val unit = if (long) "${(tileSec / 60).toInt()}분" else "${tileSec.toInt()}초"

        // 가장 흔들린 3구간 — 서로 떨어진 곳만 (긴 주행 2분, 짧은 시연은 붙어 있지 않게)
        val gap = if (long) 120.0 else topWindowSec * 2
        val top = ArrayList<ShakeEngine.Window>()
        for (w in e.windows(topWindowSec).filter { it.rms > 0.01 }.sortedByDescending { it.rms }) {
            if (top.all { abs(it.startSec - w.startSec) >= gap }) top.add(w)
            if (top.size == 3) break
        }
        val topRows = top.map { w ->
            // 위치: NaviHelper 주소("중구 세종대로 근처"), 없거나 믿을 수 없으면 경과 시간만
            val where = (if (example) null else placeFor(w)) ?: when {
                w.startSec < (if (long) 60.0 else 1.0) -> "출발 직후"
                long -> "출발 ${(w.startSec / 60).toInt()}분 뒤"
                else -> "출발 ${w.startSec.toInt()}초 뒤"
            }
            where to "${if (w.lateral) "좌우로" else "앞뒤로"} ${Forecast.NAMES[ShakeEngine.level(w.rms)]}"
        }
        top.forEachIndexed { i, w -> log("top${i + 1} ${fmt(w.startSec)}~${fmt(w.endSec)} rms=%.2f lateral=${w.lateral} gps=${w.lat},${w.lon}".format(w.rms)) }

        val brakes = e.events.count { it.kind == "급제동" }
        val stats = arrayListOf(
            "주행" to fmtDur(dur),
            "정체 반복" to if (dur >= 600) "10분에 %.1f회".format(e.stopGoPer10Min()) else "${e.stopGoSwitches}번",
            "급제동" to "${brakes}번",
            "목소리" to "${spoken}번",
        )

        val d = DiaryData(
            headline = Forecast.ARRIVE_LINE[level], fcLevel = fc?.level, actual = level, why = vsWhy(fc, level, e, dur),
            top = topRows, tiles = tiles, pins = top.map { (((it.startSec + it.endSec) / 2) / tileSec).toInt() },
            caption = "출발부터 도착까지, ${unit}마다", ticks = ticks,
            next = nextAdvice(level, top, dur, e), stats = stats, example = example,
        )
        diary.data = d; diary.invalidate()
        diaryText = listOf(
            (if (example) "[예시 데이터] " else "") + d.headline,
            (if (fc != null) "예보 ${Forecast.NAMES[fc.level]} → " else "") + "실제 ${Forecast.NAMES[level]}: ${d.why}",
            "가장 흔들린 곳: " + if (topRows.isEmpty()) "없음" else topRows.mapIndexed { i, r -> "${i + 1}) ${r.first} ${r.second}" }.joinToString(" · "),
            "다음엔: ${d.next}",
            stats.joinToString(" · ") { "${it.first} ${it.second}" } + " · 말을 참은 때 ${suppressed}번 · 흔들림 지수(자체 기준) %.2f · 의학적 판정 아님".format(Locale.US, e.index()),
            "색 칸(${unit}마다): " + tiles.joinToString("") { Forecast.NAMES[it].take(1) },
        ).joinToString("\n")
        diary.contentDescription = diaryText
    }

    /** 예보와 실제가 달랐던 까닭 한 줄 — 예: "가다 서다가 예상보다 많았어요" */
    private fun vsWhy(fc: Forecast?, actual: Int, e: ShakeEngine, dur: Double): String {
        if (fc == null) return "예보 없이 다녀온 길이에요"
        val lateralShare = e.samples.sumOf { it.ay * it.ay } / (e.samples.sumOf { it.ax * it.ax + it.ay * it.ay }.coerceAtLeast(1e-9))
        val stopGoHigh = if (dur >= 600) e.stopGoPer10Min() >= 3.0 else e.stopGoSwitches >= 3
        return when {
            actual == fc.level -> "예보대로였어요"
            actual > fc.level && stopGoHigh -> "가다 서다가 예상보다 많았어요"
            actual > fc.level && lateralShare > 0.5 -> "굽은 길에서 예상보다 더 흔들렸어요"
            actual > fc.level -> "예상보다 많이 흔들렸어요"
            !stopGoHigh -> "길이 덜 막혀 예상보다 편안했어요"
            else -> "예상보다 편안했어요"
        }
    }

    // ---- NaviHelper 주소 (주행 중 2초마다 getCurrentLocationInfo) ----
    private data class AddrFix(val tSec: Double, val address: String, val lat: Double, val lon: Double)
    private val addrFixes = ArrayList<AddrFix>()

    private fun onNaviLocation(info: CurrentLocationInfo) {
        if (phase != Phase.DRIVE && phase != Phase.ARRIVING) return
        val t = (SystemClock.elapsedRealtime() - driveStartMs) / 1000.0
        val addr = info.address ?: ""
        synchronized(addrFixes) {
            if (addrFixes.lastOrNull()?.address != addr) log("navi 주소 \"$addr\" (%.5f, %.5f)".format(info.latitude, info.longitude))
            addrFixes.add(AddrFix(t, addr, info.latitude, info.longitude))
        }
    }

    /** "중구 세종대로 근처" — 구간 가운데 시각에 가장 가까운 내비 주소. 내비 위치가 GPS와 1 km 넘게 다르면 믿지 않음. */
    private fun placeFor(w: ShakeEngine.Window): String? {
        val mid = (w.startSec + w.endSec) / 2
        val fix = synchronized(addrFixes) { addrFixes.filter { abs(it.tSec - mid) <= 10.0 }.minByOrNull { abs(it.tSec - mid) } }
        if (fix == null || fix.address.isBlank()) { log("구간 ${fmt(w.startSec)} 주소 없음 → 경과 시간"); return null }
        if (w.lat != null && w.lon != null) {
            val d = distanceM(fix.lat, fix.lon, w.lat, w.lon)
            if (d > 1000) { log("구간 ${fmt(w.startSec)} 내비 주소 \"${fix.address}\" 무시 — 내비 위치와 GPS가 %.1f km 다름 → 경과 시간".format(d / 1000)); return null }
        }
        return "${fix.address} 근처"
    }

    private fun distanceM(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val r = 6371000.0; val dLat = Math.toRadians(a2 - a1); val dLon = Math.toRadians(o2 - o1)
        val h = kotlin.math.sin(dLat / 2).let { it * it } + kotlin.math.cos(Math.toRadians(a1)) * kotlin.math.cos(Math.toRadians(a2)) * kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * r * kotlin.math.asin(sqrt(h))
    }

    private fun nextAdvice(level: Int, top: List<ShakeEngine.Window>, dur: Double, e: ShakeEngine): String = when {
        e.stopGoPer10Min() >= 3.0 && dur > 120 -> "가다 서다가 많았어요. 출발 시간도 참고해 보세요."
        level >= 2 && top.firstOrNull()?.lateral == true -> "굽은 길이 시작되기 전에 쉬는 곳에 한 번 들러요."
        level >= 2 && e.stopGoSwitches > 0 -> "가다 서다 구간 앞에서 쉬는 곳에 한 번 들러요."
        level >= 2 -> "흔들린 구간 앞에서 쉬는 곳에 한 번 들러요."
        dur > 3600 -> "중간에 쉬는 곳을 한 번 넣어 보세요."
        else -> "오늘처럼 가면 돼요."
    }

    private fun fmt(sec: Double): String { val s = sec.toInt(); return "%d:%02d".format(s / 60, s % 60) }
    private fun fmtDur(sec: Double): String { val s = sec.toInt(); return if (s >= 3600) "${s / 3600}시간 ${(s % 3600) / 60}분" else if (s >= 600) "${s / 60}분" else if (s >= 60) "${s / 60}분 ${s % 60}초" else "${s}초" }

    // =====================================================================
    // 예시 주행 40분 (심사 캡처용) — assets/example_trip_40min.csv (demo/make_example_trip.py로 만듦)
    private fun showExampleTrip() {
        try {
            val e = ShakeEngine()
            val dry = VoiceCoach(this, dryRun = true) { Log.i(TAG, "[예시] $it") }.apply { enabled = true }
            var n = 0
            assets.open(EXAMPLE_ASSET).bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("t_sec") || line.isBlank()) continue
                    val c = line.split(',')
                    val t = c[0].toDouble(); val v = c[1].toDouble(); val st = c[2].toDoubleOrNull()
                    val la = c[3].toDoubleOrNull(); val lo = c[4].toDoubleOrNull()
                    val turn = c.getOrNull(5) == "1"; val br = c.getOrNull(6) == "1"
                    e.step(t, v, st, la, lo, turn, br)
                    if (n++ % 5 == 0) dry.consider(VoiceCoach.State(t, true, turn, st, br, e.recentMaxAbsAx(3.0), null, false, e.recentRms(20.0), t, v))
                }
            }
            renderDiary(e, EXAMPLE_FORECAST, dry.spokenCount, dry.suppressedCount, example = true, topWindowSec = 30.0)
            show(Phase.DIARY)
            log("예시 주행 40분: ${n}개 샘플, " + diaryText.replace("\n", " / "))
        } catch (t: Throwable) { log("예시 주행 불러오기 실패 $t") }
    }

    // =====================================================================
    // NaviHelper — 목적지 · 회전 안내 · 혼잡 · 남은 시간
    private fun startNavi() {
        try {
            val n = NaviHelper(applicationContext); navi = n
            n.initialize({ main.post { n.addListener(naviListener); log("navi connected") } },
                { t -> main.post { plan.error = "내비 연결 실패: ${t.message}"; plan.invalidate(); log("navi failed $t") } })
        } catch (e: Throwable) { log("navi init threw $e") }
    }

    private fun requestRoute(name: String, la: Double, lo: Double) {
        destName = name; routeStart = null; tbt = emptyList(); routeState = null; forecast = null; routeStartedAt = 0L; replay = null
        plan.destName = name; plan.forecast = null; plan.busy = true; plan.error = null; plan.menuOpen = false; plan.invalidate()
        log("requestRoute $name ($la, $lo)")
        try { navi?.requestRoute(RouteInfo(la, lo, null, null, name, "", "", "", RouteOption.RECOMMENDED, "", false)) }
        catch (e: Throwable) { plan.busy = false; plan.error = "경로 요청 실패: ${e.message}"; plan.invalidate(); log("requestRoute threw $e") }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            log("navi routeStarted $routeStartInfo")
            main.post {
                routeStart = routeStartInfo; tbt = emptyList(); routeState = null
                routeStartedAt = SystemClock.elapsedRealtime()
                for (d in longArrayOf(1500, 4000, 7000, 10000)) main.postDelayed({ navi?.let { it.getTBTInfo(); it.getRouteStateInfo() } }, d)
            }
        }
        override fun onRoutingCompleted() {
            log("navi routingCompleted")
            navi?.let { it.getTBTInfo(); it.getRouteStateInfo(); it.getDestinationInfo() }
            main.postDelayed({ bringToFront() }, 1200)
        }
        override fun onTBTInfo(list: List<TBTInfo>) {
            if (!fresh()) { log("navi TBT ignored (before refresh)"); return }
            tbt = list
            nextTurnM = list.firstOrNull()?.distance
            log("navi TBT ${list.size}건: ${list.take(6).joinToString { "${it.distance.toInt()}m ${it.description}" }}")
            maybeForecast()
        }
        override fun onRouteStateInfo(info: RouteStateInfo) {
            val ts = info.trafficStatus
            log("navi routeState fresh=${fresh()} congestion=${ts?.summary?.totalCongestionDist} items=${ts?.congestionItemList?.size} roads=${ts?.congestionItemList?.take(4)?.joinToString { "${it.roadName}@${it.offset.toInt()}" }}")
            if (!fresh()) return
            routeState = info; maybeForecast()
        }
        override fun onDestinationInfo(info: DestinationInfo) { log("navi destination ${info.name} ${info.remainDistance} m ${info.remainTime} s") }
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) { main.post { onNaviLocation(info) } }
    }

    @Volatile private var routeStartedAt = 0L
    /** 개발용 빌드에서만 쓰임: 전에 기록해 둔 경로 값으로 그린 예보. 목적지를 다시 고르면 풀림 */
    private var replay: Forecast? = null
    private fun fresh() = routeStartedAt != 0L && SystemClock.elapsedRealtime() - routeStartedAt >= 1400

    private fun maybeForecast() {
        val rs = routeStart ?: return
        val st = routeState ?: return
        if (tbt.isEmpty()) return
        val ts = st.trafficStatus
        val f = Forecast(
            destination = destName,
            distanceKm = rs.distance / 1000.0,
            turns = tbt.size,
            congestionKm = minOf((ts?.summary?.totalCongestionDist ?: 0.0), rs.distance.toDouble()) / 1000.0,
            stopGoSections = ts?.congestionItemList?.count { it.trafficCongestionType == TrafficCongestionType.HEAVY } ?: 0,
            durationSec = rs.duration,
        )
        main.post { forecast = replay ?: f; renderForecast(); main.postDelayed({ bringToFront() }, 600) }
    }

    /** NaviHelper.requestRoute opens the navigation app on top — try to bring our task back (Android blocks this from background). */
    private fun bringToFront() {
        try {
            getSystemService(ActivityManager::class.java).moveTaskToFront(taskId, 0)
            log("moveTaskToFront called")
        } catch (e: Throwable) { log("moveTaskToFront failed $e") }
    }

    // =====================================================================
    // Pleos Vehicle — 조향 · 방향지시등 · 브레이크 · 좌석(동승석 · 2열) · 문
    private val seatAreas = linkedMapOf("동승석" to SeatValue.ROW_1_RIGHT, "뒷줄 왼쪽" to SeatValue.ROW_2_LEFT, "뒷줄 가운데" to SeatValue.ROW_2_CENTER, "뒷줄 오른쪽" to SeatValue.ROW_2_RIGHT)
    private val doorAreas = linkedMapOf("앞좌" to DoorValue.ROW_1_LEFT, "앞우" to DoorValue.ROW_1_RIGHT, "뒤좌" to DoorValue.ROW_2_LEFT, "뒤우" to DoorValue.ROW_2_RIGHT)
    private val seatListeners = HashMap<String, SeatOccupancyListener>()
    private val doorListeners = HashMap<String, DoorPositionListener>()

    private fun onSeat(k: String, v: Boolean?) { if (seatRaw[k] != v) log("seat $k raw=$v → ${if (v == null) "?" else if ((if (invertSeat) !v else v)) "앉음" else "비어 있음"}"); seatRaw[k] = v }
    private fun onDoor(k: String, pos: Int?) {
        val open = (pos ?: 0) != 0
        if (doorOpen[k] != open) log("door $k ${if (open) "열림" else "닫힘"}")
        doorOpen[k] = open
        if (open && phase == Phase.ARRIVING) doorOpenedSincePark = true
    }

    private val steerListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(value: Float?) { steerRaw = value?.toDouble() }
        override fun onFailed(exception: Exception) { log("steer failed ${exception.message}") }
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) { turnOn = value != null && value != TurnSignalValue.OFF; log("turn $value") }
        override fun onFailed(exception: Exception) { log("turn failed ${exception.message}") }
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(value: Boolean?) { brake = value == true }
        override fun onFailed(exception: Exception) { log("brake failed ${exception.message}") }
    }

    private fun startVehicle() {
        try {
            val v = Vehicle(applicationContext); vehicle = v
            v.initialize()
            readVehicleOnce()
            guard { v.getSteeringWheel().registerSteeringWheelAngle(steerListener) }
            guard { v.getTurnSignal().registerTurnSignalState(turnListener) }
            guard { v.getBrake().registerBrakePedalStatus(brakeListener) }
            for ((k, sv) in seatAreas) guard {
                val l = seatListeners.getOrPut(k) { object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(value: Boolean?) = onSeat(k, value)
                    override fun onFailed(exception: Exception) { log("seat $k failed ${exception.message}") }
                } }
                v.getSeat().registerSeatOccupancy(SeatArea(sv), l)
            }
            for ((k, dv) in doorAreas) guard {
                val l = doorListeners.getOrPut(k) { object : DoorPositionListener {
                    override fun onPositionUpdated(value: Int?) = onDoor(k, value)
                    override fun onFailed(exception: Exception) { log("door $k failed ${exception.message}") }
                } }
                v.getDoor().registerDoorPosition(DoorArea(dv), l)
            }
            main.postDelayed({ readVehicleOnce() }, 2500)
        } catch (e: Throwable) { log("vehicle init threw $e") }
    }

    private fun readVehicleOnce() {
        val v = vehicle ?: return
        guard { v.getSteeringWheel().getCurrentSteeringWheelAngle({ x -> steerRaw = x?.toDouble() }, { e -> log("steer get failed ${e.message}") }) }
        guard { v.getTurnSignal().getCurrentTurnSignalState({ x -> turnOn = x != null && x != TurnSignalValue.OFF }, { e -> log("turn get failed ${e.message}") }) }
        guard { v.getBrake().getCurrentBrakePedalStatus({ x -> brake = x == true }, { e -> log("brake get failed ${e.message}") }) }
        for ((k, sv) in seatAreas) guard { v.getSeat().getCurrentSeatOccupancy(SeatArea(sv), { x -> onSeat(k, x) }, { e -> log("seat $k get failed ${e.message}") }) }
        for ((k, dv) in doorAreas) guard { v.getDoor().getCurrentDoorPosition(DoorArea(dv), { x -> onDoor(k, x) }, { e -> log("door $k get failed ${e.message}") }) }
    }

    private inline fun guard(block: () -> Unit) { try { block() } catch (e: Throwable) { log("vehicle call threw $e") } }

    // =====================================================================
    // AAOS — 속도 · 기어
    private fun startCar() { car = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER) { c, ready -> if (ready) setupCar(c) } }

    private val propCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = onProp(value.propertyId, value.value)
        override fun onErrorEvent(propertyId: Int, zone: Int) { log("prop error $propertyId") }
    }

    private fun onProp(id: Int, v: Any?) {
        when (id) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> (v as? Float)?.let { speedKmh = it * 3.6 }
            VehiclePropertyIds.GEAR_SELECTION -> (v as? Int)?.let { if (it != gear) log("gear $gear → $it"); gear = it }
        }
    }

    private fun setupCar(c: Car) {
        val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        cpm?.unregisterCallback(propCallback); cpm = m
        for ((id, rate) in listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED to 10f, VehiclePropertyIds.GEAR_SELECTION to 0f)) {
            try { onProp(id, m.getProperty<Any>(id, 0)?.value) } catch (e: Exception) { log("get $id failed ${e.message}") }
            try { m.registerCallback(propCallback, id, rate) } catch (e: Exception) { log("register $id failed ${e.message}") }
        }
    }

    // =====================================================================
    // Pleos FusedLocation — 기록용 위치
    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) {
            if (data == null) return
            if (lastFixMs == 0L) log("fused 첫 위치 %.5f, %.5f".format(data.latitude, data.longitude))
            lat = data.latitude; lon = data.longitude; lastFixMs = SystemClock.elapsedRealtime()
        }
        override fun onError(exception: Exception) { log("fused error ${exception.message}") }
    }

    private fun startFused() {
        try { fused = FusedLocation(applicationContext).also { it.initialize() }; main.postDelayed({ registerFused() }, 2500) }
        catch (e: Throwable) { log("fused init threw $e") }
    }

    @Volatile private var lastFixMs = 0L

    private fun registerFused() {
        val f = fused ?: return
        try { f.unregisterFusedLocationCallback(fusedListener) } catch (_: Throwable) {}
        try { f.registerFusedLocationCallback(fusedListener); Log.i(TAG, "fused registered") } catch (e: Throwable) { log("fused register failed ${e.message}") }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent); log("onNewIntent")
        handleIntro(intent)
        // 개발용 빌드에서만: 문장 길이 재기 — am start -n …/.MainActivity --es say "문장"
        val say = intent?.getStringExtra("say")
        val debug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (say != null && debug) voice.speakTest(say)
        // 개발용 빌드에서만: 시연 녹화 전에 출발 전 화면으로 — am start -n …/.MainActivity --es go plan
        if (debug && intent?.getStringExtra("go") == "plan" && phase == Phase.DIARY) show(Phase.PLAN)
        // 개발용 빌드에서만: 단계별 모습 보기 — am start -n …/.MainActivity --ei look 0 (0 맑음 ~ 3 출렁, -1이면 원래대로)
        // 개발용 빌드에서만: 전에 기록해 둔 경로 값으로 예보 카드를 다시 그림 (그날의 화면을 다시 볼 때)
        //   am start -n …/.MainActivity --es fc "|23.0|14|13.0|10|4080"  (목적지(비우면 지금 목적지)|km|굽은 길|혼잡 km|가다 서다|초), off 이면 풀림
        val fc = intent?.getStringExtra("fc")
        if (debug && fc != null) {
            val a = fc.split("|")
            replay = if (a.size == 6) runCatching { Forecast(a[0].ifEmpty { destName }, a[1].toDouble(), a[2].toInt(), a[3].toDouble(), a[4].toInt(), a[5].toInt()) }.getOrNull() else null
            replay?.let { forecast = it; log("기록해 둔 경로 값으로 예보를 다시 그림"); renderForecast() }
        }
        if (debug && intent?.hasExtra("look") == true) {
            plan.lookLevel = intent.getIntExtra("look", -1).takeIf { it in 0..3 }; plan.invalidate()
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        flushCsv()
        voice.release()
        try { cpm?.unregisterCallback(propCallback); car?.disconnect() } catch (_: Exception) {}
        try { fused?.unregisterFusedLocationCallback(fusedListener); fused?.release() } catch (_: Exception) {}
        try { navi?.removeListener(naviListener); navi?.release() } catch (_: Exception) {}
        vehicle?.let { v ->
            try {
                v.getSteeringWheel().unregisterSteeringWheelAngle(steerListener)
                v.getTurnSignal().unregisterTurnSignalState(turnListener)
                v.getBrake().unregisterBrakePedalStatus(brakeListener)
                for ((k, sv) in seatAreas) seatListeners[k]?.let { v.getSeat().unregisterSeatOccupancy(SeatArea(sv), it) }
                for ((k, dv) in doorAreas) doorListeners[k]?.let { v.getDoor().unregisterDoorPosition(DoorArea(dv), it) }
            } catch (_: Exception) {}
            v.release()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MotionForecast"
        private const val EXAMPLE_ASSET = "example_trip_40min.csv"
        private const val INTRO_DONE = "intro_done"
        private val DESTINATIONS = listOf(
            Triple("판교역", 37.394761, 127.111217),
            Triple("서울역", 37.554648, 126.970702),
            Triple("강릉역", 37.764067, 128.899536),
        )
        /** 예시 주행(잠실 → 양평 두물머리, 40분 · 약 29 km)의 출발 전 예보 — 예시 값: 굽은 길 50번 · 혼잡 6 km(21 %) · 가다 서다 2구간 → 3.01 울렁 */
        private val EXAMPLE_FORECAST = Forecast("양평 두물머리(예시)", 29.0, 50, 6.0, 2, 40 * 60)
    }
}
