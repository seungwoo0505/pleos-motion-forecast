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
import android.app.AlertDialog
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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 「멀미 예보」 v3 — 기획서 v2(4. 제출서류/261107_Claude_기획서_초안v2.md) 3장 · 부록 C 기준.
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

    // ---- views ----
    private lateinit var planView: LinearLayout
    private lateinit var driveView: LinearLayout
    private lateinit var diaryView: LinearLayout
    private lateinit var settingsView: LinearLayout
    private lateinit var planIcon: WeatherIconView
    private lateinit var planHeadline: TextView
    private lateinit var planChar: TextView
    private lateinit var planFacts: TextView
    private lateinit var planDetail: TextView
    private lateinit var planPrep: TextView
    private lateinit var planStatus: TextView
    private lateinit var devDetail: TextView
    private lateinit var routeStatus: TextView
    private lateinit var voiceSwitch: Switch
    private val passengerButtons = LinkedHashMap<Passenger, TextView>()
    private lateinit var driveText: TextView
    private lateinit var driveTitle: TextView
    private lateinit var diaryIcon: WeatherIconView
    private lateinit var diaryHeadline: TextView
    private lateinit var diaryVs: TextView
    private lateinit var diaryGraph: ShakeGraphView
    private lateinit var diaryTop: TextView
    private lateinit var diaryStats: TextView
    private lateinit var diaryNext: TextView

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
        show(Phase.PLAN)
        renderPrep()
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
    // UI
    private fun tv(size: Float, color: Int = Palette.INK, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card(bg: Int) = GradientDrawable().apply { cornerRadius = 28f; setColor(bg) }
    private fun chip(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = 40f; setColor(if (selected) Color.rgb(30, 90, 160) else Color.WHITE); setStroke(3, Color.rgb(30, 90, 160))
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(250, 250, 248)) }

        // ---------- ① 출발 전 (P) ----------
        planView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(56, 36, 56, 28) }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(tv(44f, bold = true).apply {
            text = "멀미 예보"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = "⋮"; textSize = 44f; setTextColor(Palette.MUTED); setPadding(40, 0, 16, 0)
            contentDescription = "설정"
            setOnClickListener { openSettings(true) }
        })
        planView.addView(titleRow)
        planView.addView(tv(24f, Palette.MUTED).apply { text = "오늘 길 흔들림을 날씨처럼 미리 알려 드려요"; setPadding(0, 0, 0, 18) })

        val destRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        destRow.addView(tv(26f).apply { text = "목적지  " })
        for ((name, la, lo) in DESTINATIONS) destRow.addView(Button(this).apply {
            text = name; textSize = 24f; setOnClickListener { requestRoute(name, la, lo) }
        })
        routeStatus = tv(22f, Palette.MUTED).apply { setPadding(24, 0, 0, 0) }
        destRow.addView(routeStatus)
        planView.addView(destRow)

        // 탑승자 유형 + 목소리 안내 토글 (기획서 v2 음성 사양: 기본 꺼짐)
        val whoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 14, 0, 0) }
        whoRow.addView(tv(26f).apply { text = "탑승자  " })
        for (p in Passenger.values()) {
            val b = TextView(this).apply {
                text = p.label; textSize = 24f; gravity = Gravity.CENTER; setPadding(44, 12, 44, 12)
                setOnClickListener { pickPassenger(p) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 18 }
            }
            passengerButtons[p] = b; whoRow.addView(b)
        }
        whoRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        voiceSwitch = Switch(this).apply {
            text = "목소리 안내  "; textSize = 26f; setTextColor(Palette.INK); isChecked = false
            setOnCheckedChangeListener { _, on -> setVoice(on, "토글") }
        }
        whoRow.addView(voiceSwitch)
        planView.addView(whoRow)
        refreshPassengerChips()

        val forecastCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(36, 28, 36, 28); background = card(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        }
        planIcon = WeatherIconView(this).apply { layoutParams = LinearLayout.LayoutParams(360, 360) }
        forecastCard.addView(planIcon)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 0, 0, 0) }
        planHeadline = tv(40f, bold = true).apply { text = "목적지를 고르면 오늘 길 흔들림을 알려 드려요" }
        planChar = tv(27f, Color.rgb(30, 90, 160)).apply { setPadding(0, 10, 0, 0) }
        planFacts = tv(30f).apply { setPadding(0, 12, 0, 4) }
        planDetail = tv(20f, Palette.MUTED)
        planPrep = tv(28f).apply { setPadding(0, 20, 0, 0) }
        col.addView(planHeadline); col.addView(planChar); col.addView(planFacts); col.addView(planDetail); col.addView(planPrep)
        forecastCard.addView(col)
        planView.addView(forecastCard)

        // ---------- 설정 (⋮) — 보정 · 시연 모드 · 개발용 정보 · 심사 캡처용 예시 ----------
        settingsView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(56, 40, 56, 32) }
        val sTitle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sTitle.addView(TextView(this).apply {
            text = "←"; textSize = 40f; setTextColor(Palette.INK); setPadding(0, 0, 28, 0)
            contentDescription = "돌아가기"; setOnClickListener { openSettings(false) }
        })
        sTitle.addView(tv(40f, bold = true).apply { text = "설정" })
        settingsView.addView(sTitle)
        fun section(t: String) = settingsView.addView(tv(22f, Palette.MUTED, bold = true).apply { text = t; setPadding(0, 32, 0, 8) })
        fun toggle(label: String, initial: Boolean, f: (Boolean) -> Unit) = settingsView.addView(CheckBox(this).apply {
            text = label; textSize = 24f; setTextColor(Palette.INK); isChecked = initial; setPadding(8, 10, 0, 10); setOnCheckedChangeListener { _, c -> f(c) }
        })
        section("값 보정 (에뮬레이터에서 반대로 들어옴 · 실차 확인 필요)")
        toggle("착석 반대 보정", invertSeat) { invertSeat = it; log("setting invertSeat=$it") }
        toggle("조향 부호 보정", invertSteer) { invertSteer = it; log("setting invertSteer=$it") }
        section("시연")
        toggle("시연 모드 (도착 대기 5초 · 목소리 간격 15초 · 구간 3초)", demoMode) { setDemo(it) }
        section("심사 캡처용")
        settingsView.addView(Button(this).apply {
            text = "예시 주행 40분 — 승차감 일기 보기"; textSize = 22f; setOnClickListener { showExampleTrip() }
        })
        section("개발용 정보")
        planStatus = tv(21f, Palette.MUTED).apply { setPadding(8, 0, 0, 6) }
        settingsView.addView(planStatus)
        devDetail = tv(21f, Palette.MUTED).apply { setPadding(8, 0, 0, 0) }
        settingsView.addView(devDetail)

        // ---------- ② 주행 중 (D) — 화면은 자동 차단, 목소리만 ----------
        driveView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(60, 60, 60, 60) }
        driveTitle = tv(44f, bold = true).apply { text = DRIVE_TITLE; gravity = Gravity.CENTER }
        driveView.addView(driveTitle)
        driveText = tv(26f, Palette.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, 30, 0, 0) }
        driveView.addView(driveText)

        // ---------- ③ 도착 후 — 승차감 일기 ----------
        diaryView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(56, 30, 56, 24) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        diaryIcon = WeatherIconView(this).apply { layoutParams = LinearLayout.LayoutParams(180, 180) }
        head.addView(diaryIcon)
        val hcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 0, 0, 0) }
        hcol.addView(tv(24f, Palette.MUTED).apply { text = "승차감 일기" })
        diaryHeadline = tv(38f, bold = true)
        diaryVs = tv(26f, Color.rgb(30, 90, 160)).apply { setPadding(0, 6, 0, 0) }
        hcol.addView(diaryHeadline); hcol.addView(diaryVs)
        head.addView(hcol)
        diaryView.addView(head)
        diaryGraph = ShakeGraphView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 420).apply { topMargin = 12 }
            background = card(Color.WHITE)
        }
        diaryView.addView(diaryGraph)
        diaryTop = tv(26f).apply { setPadding(0, 16, 0, 0) }
        diaryStats = tv(20f, Palette.MUTED).apply { setPadding(0, 10, 0, 0) }
        diaryNext = tv(30f, bold = true).apply { setPadding(0, 14, 0, 8); setTextColor(Color.rgb(30, 90, 160)) }
        diaryView.addView(diaryTop); diaryView.addView(diaryNext); diaryView.addView(diaryStats)
        diaryView.addView(Button(this).apply { text = "새 여행"; textSize = 22f; setOnClickListener { show(Phase.PLAN) } })

        for (v in listOf(planView, settingsView, driveView, diaryView)) root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    // ---- 탑승자 유형 · 목소리 안내 ----
    private fun pickPassenger(p: Passenger) {
        passenger = p
        log("passenger ${p.label}")
        refreshPassengerChips()
        renderPrep()
        if (!voice.enabled && !askedVoice) {
            askedVoice = true
            AlertDialog.Builder(this)
                .setTitle("목소리 안내를 켤까요?")
                .setMessage("달리는 동안 굽은 길 앞에서 짧게 알려 드려요. 차 안 모두에게 들려요.")
                .setPositiveButton("켜기") { _, _ -> voiceSwitch.isChecked = true }
                .setNegativeButton("나중에") { _, _ -> log("voice 묻기: 나중에") }
                .show()
        }
    }

    private fun setVoice(on: Boolean, why: String) { voice.enabled = on; log("voice enabled=$on ($why)") }

    private fun refreshPassengerChips() {
        for ((p, b) in passengerButtons) {
            val sel = p == passenger
            b.background = chip(sel); b.setTextColor(if (sel) Color.WHITE else Color.rgb(30, 90, 160))
        }
    }

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
        planView.visibility = if (p == Phase.PLAN && !settingsOpen) View.VISIBLE else View.GONE
        settingsView.visibility = if (p == Phase.PLAN && settingsOpen) View.VISIBLE else View.GONE
        driveView.visibility = if (p == Phase.DRIVE || p == Phase.ARRIVING) View.VISIBLE else View.GONE
        diaryView.visibility = if (p == Phase.DIARY) View.VISIBLE else View.GONE
        driveTitle.text = if (p == Phase.ARRIVING) ARRIVING_TITLE else DRIVE_TITLE
        Log.i(TAG, "phase → $p")
    }

    private fun renderForecast() {
        val f = forecast ?: return
        planIcon.level = f.level
        planHeadline.text = "오늘 길 흔들림 예보: ${Forecast.NAMES[f.level]}"
        planChar.text = Forecast.PLAN_LINE[f.level]
        planFacts.text = f.facts()
        planDetail.text = SpannableStringBuilder().apply {
            append("%s %.1f km · 혼잡 %.0f%%   ·   흔들림 지수(자체 기준) ".format(Locale.US, f.destination, f.distanceKm, f.congestionRatio * 100))
            val s = length
            append("%.2f".format(Locale.US, f.score))
            setSpan(RelativeSizeSpan(0.75f), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        devDetail.text = "예보 계산: ${f.detail()} — 굽은 길/km + 혼잡 비율 × ${Forecast.CONGESTION_WEIGHT} + 시간(h), 기준 1.5 / 3 / 4.5 (가정값)"
        renderPrep()
        log("forecast ${f.detail()} → ${Forecast.NAMES[f.level]} (${f.facts()})")
    }

    private fun renderPrep() {
        val f = forecast ?: run { planPrep.text = ""; return }
        val who = passenger?.let { " · ${it.label}" } ?: ""
        planPrep.text = "준비 3가지$who\n" + Forecast.prep(f.level, passenger).joinToString("\n") { "  •  $it" }
    }

    private fun seatText(k: String) = when (seatOccupied(k)) { true -> "앉음"; false -> "비어 있음"; else -> "확인 중" }

    private fun renderStatus() {
        val seats = seatRaw.keys.joinToString(" · ") { "$it ${seatText(it)}" }
        val g = when (gear) { VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_DRIVE -> "D"; VehicleGear.GEAR_REVERSE -> "R"; VehicleGear.GEAR_NEUTRAL -> "N"; else -> "$gear" }
        val st = steerDeg?.let { "${"%.0f".format(it)}°" } ?: "확인 중"
        val pos = if (lat != null) "%.5f, %.5f".format(lat, lon) else "위치 확인 중"
        planStatus.text = "좌석: $seats\n기어 $g · ${"%.0f".format(speedKmh)} km/h · 조향 $st · $pos · 목소리 안내 ${if (voice.enabled) "켬" else "끔"}"
        if (phase == Phase.DRIVE || phase == Phase.ARRIVING) {
            val t = engine.durationSec()
            driveText.text = "기록 ${"%.0f".format(t)}초 · ${"%.0f".format(speedKmh)} km/h · 조향 $st · 흔들림 ${"%.2f".format(engine.recentRms(5.0))} m/s²\n" +
                "목소리 ${voice.spokenCount}번 · 참은 때 ${voice.suppressedCount}번" +
                (if (phase == Phase.ARRIVING) "\nP ${"%.0f".format((SystemClock.elapsedRealtime() - parkSinceMs) / 1000.0)}초 / ${arrivalWaitSec.toInt()}초 · 문 열림 ${if (doorOpenedSincePark) "예" else "아직"}" else "")
        }
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
                    stepEngine(now)
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
        driveStartMs = now
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
        log("도착 — 일기: ${diaryHeadline.text} / ${diaryVs.text} / ${diaryNext.text} / ${diaryStats.text}")
        val summary = File(csv?.parentFile, (csv?.nameWithoutExtension ?: "trip") + "_summary.txt")
        summary.writeText(listOf(diaryHeadline.text, diaryVs.text, diaryTop.text, diaryNext.text, diaryStats.text, "", "목소리: ${voice.history} (마지막 말한 길이 ${voice.lastSpeakMs} ms)", "", "기록:").joinToString("\n") + "\n" + tripLog.joinToString("\n") + "\n")
    }

    /** 승차감 일기 — 실제 주행과 예시 주행이 같은 화면을 씀 */
    private fun renderDiary(e: ShakeEngine, fc: Forecast?, spoken: Int, suppressed: Int, example: Boolean, topWindowSec: Double) {
        val dur = e.durationSec()
        val overall = sqrt(e.samples.sumOf { it.shake * it.shake } / e.samples.size.coerceAtLeast(1))
        val level = ShakeEngine.level(overall)
        val long = dur >= 180
        val per = e.windows(if (long) 30.0 else 1.0).map { it.startSec to it.rms }
        // 가장 흔들린 3구간 — 서로 떨어진 곳만 (긴 주행 2분, 짧은 시연은 붙어 있지 않게)
        val gap = if (long) 120.0 else topWindowSec * 2
        val top = ArrayList<ShakeEngine.Window>()
        for (w in e.windows(topWindowSec).filter { it.rms > 0.01 }.sortedByDescending { it.rms }) {
            if (top.all { abs(it.startSec - w.startSec) >= gap }) top.add(w)
            if (top.size == 3) break
        }
        diaryGraph.points = per; diaryGraph.top = top; diaryGraph.totalSec = dur; diaryGraph.invalidate()
        diaryIcon.level = level
        diaryHeadline.text = Forecast.ARRIVE_LINE[level]
        diaryVs.text = vsLine(fc, level, e, dur)
        diaryTop.text = "가장 흔들린 3구간\n" + if (top.isEmpty()) "  (흔들린 구간 없음)" else top.mapIndexed { i, w ->
            // 위치: NaviHelper 주소("중구 세종대로 근처"), 없거나 믿을 수 없으면 경과 시간만
            val where = (if (example) null else placeFor(w)) ?: if (long) "출발 ${(w.startSec / 60).toInt()}분 뒤" else "출발 후 ${fmt(w.startSec)}~${fmt(w.endSec)}"
            "  ${"①②③"[i]}  $where · ${if (w.lateral) "좌우" else "앞뒤"} 흔들림 · ${Forecast.NAMES[ShakeEngine.level(w.rms)]}"
        }.joinToString("\n")
        top.forEachIndexed { i, w -> log("top${i + 1} ${fmt(w.startSec)}~${fmt(w.endSec)} rms=%.2f lateral=${w.lateral} gps=${w.lat},${w.lon}".format(w.rms)) }
        val brakes = e.events.count { it.kind == "급제동" }
        val stopGo = if (dur >= 600) "%.1f회/10분".format(e.stopGoPer10Min()) else "${e.stopGoSwitches}번"
        diaryStats.text = SpannableStringBuilder().apply {
            if (example) append("예시 주행 데이터(시뮬레이션) · ")
            append("주행 ${fmtDur(dur)} · 흔들림 지수(자체 기준) ")
            val s = length
            append("%.2f".format(Locale.US, e.index()))
            setSpan(RelativeSizeSpan(0.8f), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" · 의학적 판정 아님 · 정체 반복 $stopGo · 급제동 ${brakes}번(기록만) · 목소리 ${spoken}번, 참은 때 ${suppressed}번")
        }
        diaryNext.text = "다음엔: " + nextAdvice(level, top, dur, e)
    }

    /** 예보 대비 실제 한 줄 — 예: "예보 울렁 → 실제 출렁: 가다 서다가 예상보다 많았어요" */
    private fun vsLine(fc: Forecast?, actual: Int, e: ShakeEngine, dur: Double): String {
        if (fc == null) return "예보 없이 다녀온 길: 실제 ${Forecast.NAMES[actual]}"
        val head = "예보 ${Forecast.NAMES[fc.level]} → 실제 ${Forecast.NAMES[actual]}: "
        val lateralShare = e.samples.sumOf { it.ay * it.ay } / (e.samples.sumOf { it.ax * it.ax + it.ay * it.ay }.coerceAtLeast(1e-9))
        val stopGoHigh = if (dur >= 600) e.stopGoPer10Min() >= 3.0 else e.stopGoSwitches >= 3
        return head + when {
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
        e.stopGoPer10Min() >= 3.0 && dur > 120 -> "30분 일찍 출발하면 가다 서다가 줄어요."
        level >= 2 && top.firstOrNull()?.lateral == true -> "굽은 길이 시작되기 전에 쉬는 곳에 한 번 들러요."
        level >= 2 -> "가다 서다 구간 앞에서 쉬는 곳에 한 번 들러요."
        dur > 3600 -> "중간에 쉬는 곳을 한 번 넣어 보세요."
        else -> "오늘처럼 가면 돼요."
    }

    private fun fmt(sec: Double): String { val s = sec.toInt(); return "%d:%02d".format(s / 60, s % 60) }
    private fun fmtDur(sec: Double): String { val s = sec.toInt(); return if (s >= 3600) "${s / 3600}시간 ${(s % 3600) / 60}분" else if (s >= 60) "${s / 60}분 ${s % 60}초" else "${s}초" }

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
            log("예시 주행 40분: ${n}개 샘플, ${diaryHeadline.text} / ${diaryVs.text} / 목소리 ${dry.spokenCount}번")
        } catch (t: Throwable) { log("예시 주행 불러오기 실패 $t") }
    }

    // =====================================================================
    // NaviHelper — 목적지 · 회전 안내 · 혼잡 · 남은 시간
    private fun startNavi() {
        try {
            val n = NaviHelper(applicationContext); navi = n
            n.initialize({ main.post { n.addListener(naviListener); log("navi connected") } },
                { t -> main.post { routeStatus.text = "내비 연결 실패: ${t.message}"; log("navi failed $t") } })
        } catch (e: Throwable) { log("navi init threw $e") }
    }

    private fun requestRoute(name: String, la: Double, lo: Double) {
        destName = name; routeStart = null; tbt = emptyList(); routeState = null; forecast = null; routeStartedAt = 0L
        routeStatus.text = "$name 경로 찾는 중…"
        log("requestRoute $name ($la, $lo)")
        try { navi?.requestRoute(RouteInfo(la, lo, null, null, name, "", "", "", RouteOption.RECOMMENDED, "", false)) }
        catch (e: Throwable) { routeStatus.text = "경로 요청 실패: ${e.message}"; log("requestRoute threw $e") }
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
        main.post { forecast = f; routeStatus.text = "$destName 경로 받음"; renderForecast(); main.postDelayed({ bringToFront() }, 600) }
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
        // 개발용 빌드에서만: 문장 길이 재기 — am start -n …/.MainActivity --es say "문장"
        val say = intent?.getStringExtra("say")
        if (say != null && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) voice.speakTest(say)
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
        private const val DRIVE_TITLE = "주행 중 — 화면은 쉬고, 목소리로 챙겨요"
        private const val ARRIVING_TITLE = "도착 확인 중 — 문을 열면 승차감 일기가 열려요"
        private const val EXAMPLE_ASSET = "example_trip_40min.csv"
        private val DESTINATIONS = listOf(
            Triple("판교역", 37.394761, 127.111217),
            Triple("서울역", 37.554648, 126.970702),
            Triple("강릉역", 37.764067, 128.899536),
        )
        /** 예시 주행(잠실 → 양평 두물머리, 40분 · 약 29 km)의 출발 전 예보 — 예시 값: 굽은 길 50번 · 혼잡 6 km(21 %) · 가다 서다 2구간 → 3.01 울렁 */
        private val EXAMPLE_FORECAST = Forecast("양평 두물머리(예시)", 29.0, 50, 6.0, 2, 40 * 60)
    }
}
