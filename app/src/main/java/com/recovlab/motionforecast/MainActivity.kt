package com.recovlab.motionforecast

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.TrafficCongestionType
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
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * 「멀미 예보」 MVP — 설계서 v1(2. 공모전/18. 현대차 Pleos 앱 공모전/1. 기획/261106_Claude_멀미예보_설계v1.md).
 * 화면은 P에서만(출발 전 날씨 카드 · 도착 후 승차감 일기), 주행 중은 목소리만.
 * Log tag: MotionForecast
 */
class MainActivity : Activity() {

    private enum class Phase { PLAN, DRIVE, ARRIVING, DIARY }

    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    // ---- settings (설계서 3장: 보정 스위치, 기본 켬) ----
    private var invertSeat = true
    private var invertSteer = true
    private var demoMode = false
    private val arrivalWaitSec get() = if (demoMode) 5.0 else 30.0
    private val topWindowSec get() = if (demoMode) 3.0 else 30.0

    // ---- live vehicle values ----
    @Volatile private var speedKmh = 0.0
    @Volatile private var gear = VehicleGear.GEAR_PARK
    @Volatile private var steerRaw: Double? = null
    @Volatile private var turnOn = false
    @Volatile private var brake = false
    @Volatile private var lat: Double? = null
    @Volatile private var lon: Double? = null
    @Volatile private var nextTurnM: Double? = null
    private val seatRaw = linkedMapOf<String, Boolean?>("왼쪽" to null, "가운데" to null, "오른쪽" to null)
    private val doorOpen = linkedMapOf<String, Boolean>()

    private val steerDeg: Double? get() = steerRaw?.let { if (invertSteer) -it else it }
    private fun seatOccupied(k: String): Boolean? = seatRaw[k]?.let { if (invertSeat) !it else it }
    private val rearOccupied: Boolean get() = seatRaw.keys.any { seatOccupied(it) == true }

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
    private lateinit var planIcon: WeatherIconView
    private lateinit var planHeadline: TextView
    private lateinit var planFacts: TextView
    private lateinit var planDetail: TextView
    private lateinit var planPrep: TextView
    private lateinit var planStatus: TextView
    private lateinit var routeStatus: TextView
    private lateinit var driveText: TextView
    private lateinit var diaryIcon: WeatherIconView
    private lateinit var diaryHeadline: TextView
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

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(250, 250, 248)) }

        // ---------- ① 출발 전 (P) ----------
        planView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(56, 40, 56, 32) }
        planView.addView(tv(44f, bold = true).apply { text = "멀미 예보" })
        planView.addView(tv(24f, Palette.MUTED).apply { text = "오늘 길, 뒷자리 날씨를 미리 알려 드려요"; setPadding(0, 0, 0, 22) })

        val destRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        destRow.addView(tv(26f).apply { text = "목적지  " })
        for ((name, la, lo) in DESTINATIONS) destRow.addView(Button(this).apply {
            text = name; textSize = 24f; setOnClickListener { requestRoute(name, la, lo) }
        })
        routeStatus = tv(22f, Palette.MUTED).apply { setPadding(24, 0, 0, 0) }
        destRow.addView(routeStatus)
        planView.addView(destRow)

        val forecastCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(36, 32, 36, 32); background = card(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 }
        }
        planIcon = WeatherIconView(this).apply { layoutParams = LinearLayout.LayoutParams(380, 380) }
        forecastCard.addView(planIcon)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 0, 0, 0) }
        planHeadline = tv(40f, bold = true).apply { text = "목적지를 고르면 뒷자리 날씨를 알려 드려요" }
        planFacts = tv(32f).apply { setPadding(0, 16, 0, 4) }
        planDetail = tv(20f, Palette.MUTED)
        planPrep = tv(29f).apply { setPadding(0, 24, 0, 0) }
        col.addView(planHeadline); col.addView(planFacts); col.addView(planDetail); col.addView(planPrep)
        forecastCard.addView(col)
        planView.addView(forecastCard)

        planStatus = tv(22f, Palette.MUTED).apply { setPadding(4, 26, 0, 8) }
        planView.addView(planStatus)
        val toggles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun toggle(label: String, initial: Boolean, f: (Boolean) -> Unit) = toggles.addView(CheckBox(this).apply {
            text = label; textSize = 22f; setTextColor(Palette.INK); isChecked = initial; setPadding(0, 0, 40, 0); setOnCheckedChangeListener { _, c -> f(c) }
        })
        toggle("착석 반대 보정", invertSeat) { invertSeat = it; log("setting invertSeat=$it") }
        toggle("조향 부호 보정", invertSteer) { invertSteer = it; log("setting invertSteer=$it") }
        toggle("시연 모드 (도착 대기 5초 · 목소리 간격 15초)", demoMode) { setDemo(it) }
        planView.addView(toggles)

        // ---------- ② 주행 중 (D) — 화면은 자동 차단, 목소리만 ----------
        driveView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(60, 60, 60, 60) }
        driveView.addView(tv(44f, bold = true).apply { text = "주행 중 — 화면은 쉬고, 목소리로 챙겨요"; gravity = Gravity.CENTER })
        driveText = tv(26f, Palette.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, 30, 0, 0) }
        driveView.addView(driveText)

        // ---------- ③ 도착 후 — 승차감 일기 ----------
        diaryView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(56, 36, 56, 28) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        diaryIcon = WeatherIconView(this).apply { layoutParams = LinearLayout.LayoutParams(190, 190) }
        head.addView(diaryIcon)
        val hcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 0, 0, 0) }
        hcol.addView(tv(24f, Palette.MUTED).apply { text = "승차감 일기" })
        diaryHeadline = tv(38f, bold = true)
        hcol.addView(diaryHeadline)
        head.addView(hcol)
        diaryView.addView(head)
        diaryGraph = ShakeGraphView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 430).apply { topMargin = 16 }
            background = card(Color.WHITE)
        }
        diaryView.addView(diaryGraph)
        diaryTop = tv(26f).apply { setPadding(0, 18, 0, 0) }
        diaryStats = tv(20f, Palette.MUTED).apply { setPadding(0, 12, 0, 0) }
        diaryNext = tv(30f, bold = true).apply { setPadding(0, 16, 0, 10); setTextColor(Color.rgb(30, 90, 160)) }
        diaryView.addView(diaryTop); diaryView.addView(diaryNext); diaryView.addView(diaryStats)
        diaryView.addView(Button(this).apply { text = "새 여행"; textSize = 22f; setOnClickListener { show(Phase.PLAN) } })

        for (v in listOf(planView, driveView, diaryView)) root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun setDemo(on: Boolean) {
        demoMode = on
        voice.minIntervalSec = if (on) 15.0 else 600.0
        log("setting demoMode=$on")
    }

    private fun show(p: Phase) {
        phase = p
        planView.visibility = if (p == Phase.PLAN) View.VISIBLE else View.GONE
        driveView.visibility = if (p == Phase.DRIVE || p == Phase.ARRIVING) View.VISIBLE else View.GONE
        diaryView.visibility = if (p == Phase.DIARY) View.VISIBLE else View.GONE
        Log.i(TAG, "phase → $p")
    }

    private fun renderForecast() {
        val f = forecast ?: return
        planIcon.level = f.level
        planHeadline.text = "오늘 길, 뒷자리 날씨는 '${Forecast.NAMES[f.level]}'입니다."
        planFacts.text = f.facts()
        planDetail.text = f.detail() + "  (가정값 기준)"
        planPrep.text = "준비 3가지\n" + Forecast.prep(f.level).joinToString("\n") { "  •  $it" }
        log("forecast ${f.detail()} → ${Forecast.NAMES[f.level]} (${f.facts()})")
    }

    private fun renderStatus() {
        val seats = seatRaw.keys.joinToString("  ") { k -> "$k ${when (seatOccupied(k)) { true -> "앉음"; false -> "빔"; else -> "?" }}" }
        val g = when (gear) { VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_DRIVE -> "D"; VehicleGear.GEAR_REVERSE -> "R"; VehicleGear.GEAR_NEUTRAL -> "N"; else -> "$gear" }
        val st = steerDeg?.let { "${"%.0f".format(it)}°" } ?: "?"
        val pos = if (lat != null) "%.5f, %.5f".format(lat, lon) else "위치 대기"
        planStatus.text = "뒷좌석: $seats   |   기어 $g · ${"%.0f".format(speedKmh)} km/h · 조향 $st · $pos"
        if (phase == Phase.DRIVE || phase == Phase.ARRIVING) {
            val t = engine.durationSec()
            driveText.text = "기록 ${"%.0f".format(t)}초 · ${"%.0f".format(speedKmh)} km/h · 조향 $st · 흔들림 ${"%.2f".format(engine.recentRms(5.0))} m/s²\n" +
                "목소리 ${voice.spokenCount}번 · 참은 때 ${voice.suppressedCount}번" +
                (if (phase == Phase.ARRIVING) "\n도착 확인 중 — P ${"%.0f".format((SystemClock.elapsedRealtime() - parkSinceMs) / 1000.0)}초 / ${arrivalWaitSec.toInt()}초 · 문 열림 ${if (doorOpenedSincePark) "예" else "아직"}" else "")
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
        driveStartMs = now
        show(Phase.DRIVE)
        val dir = File(filesDir, "trips").apply { mkdirs() }
        csv = File(dir, "trip_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv").also {
            it.writeText("t_sec,speed_kmh,steer_deg,lat,lon,ax,ay,shake,turn,brake,rear_occupied\n")
        }
        log("D — 기록 시작 (목적지 ${destName.ifEmpty { "없음" }}, 날씨 ${forecast?.let { Forecast.NAMES[it.level] } ?: "-"}, 뒷좌석 ${if (rearOccupied) "있음" else "없음"})")
    }

    private val csvBuf = StringBuilder()
    private fun stepEngine(now: Long) {
        val t = (now - driveStartMs) / 1000.0
        val s = engine.step(t, speedKmh, steerDeg, lat, lon, turnOn, brake)
        csvBuf.append("%.1f,%.1f,%s,%s,%s,%.3f,%.3f,%.3f,%d,%d,%d\n".format(Locale.US, s.tSec, s.speedKmh, s.steerDeg?.let { "%.1f".format(Locale.US, it) } ?: "",
            s.lat?.let { "%.6f".format(Locale.US, it) } ?: "", s.lon?.let { "%.6f".format(Locale.US, it) } ?: "", s.ax, s.ay, s.shake,
            if (s.turnOn) 1 else 0, if (s.brake) 1 else 0, if (rearOccupied) 1 else 0))
        if (csvBuf.length > 4000) flushCsv()
    }

    private fun flushCsv() { csv?.appendText(csvBuf.toString()); csvBuf.setLength(0) }

    private fun considerVoice(now: Long) {
        val t = (now - driveStartMs) / 1000.0
        voice.consider(VoiceCoach.State(
            tSec = t, rearOccupied = rearOccupied, turnOn = turnOn, steerDeg = steerDeg, brake = brake,
            recentMaxAbsAx = engine.recentMaxAbsAx(3.0), nextTurnM = nextTurnM,
            recentShakeRms = engine.recentRms(if (demoMode) 6.0 else 20.0), drivingSec = t,
        ))
    }

    private fun finishTrip() {
        flushCsv()
        val dur = engine.durationSec()
        val overall = sqrt(engine.samples.sumOf { it.shake * it.shake } / engine.samples.size.coerceAtLeast(1))
        val level = ShakeEngine.level(overall)
        val per = engine.windows(1.0).map { it.startSec to it.rms }
        val top = engine.windows(topWindowSec).filter { it.rms > 0.01 }.sortedByDescending { it.rms }.take(3)
        diaryGraph.points = per; diaryGraph.top = top; diaryGraph.invalidate()
        diaryIcon.level = level
        diaryHeadline.text = "오늘 뒷자리 날씨: ${Forecast.NAMES[level]} — ${REVIEW[level]}"
        diaryTop.text = "가장 흔들린 3구간\n" + if (top.isEmpty()) "  (흔들린 구간 없음)" else top.mapIndexed { i, w ->
            val where = if (w.lat != null) "  ·  %.4f, %.4f 근처".format(w.lat, w.lon) else ""
            "  ${"①②③"[i]}  ${fmt(w.startSec)}~${fmt(w.endSec)}  ${if (w.lateral) "좌우" else "앞뒤"} 흔들림 · ${Forecast.NAMES[ShakeEngine.level(w.rms)]} (%.2f m/s²)$where".format(w.rms)
        }.joinToString("\n")
        val brakes = engine.events.count { it.kind == "급제동" }
        val stopGo = if (dur >= 600) "%.1f회/10분".format(engine.stopGoPer10Min()) else "${engine.stopGoSwitches}번 (10분 미만 주행)"
        diaryStats.text = "주행 ${fmt(dur)} · 흔들림 지수 %.2f (자체 지수, 의학적 판정 아님) · 정체 반복 $stopGo · 급제동 ${brakes}번(기록만) · 목소리 ${voice.spokenCount}번, 참은 때 ${voice.suppressedCount}번"
            .format(engine.index()) + (forecast?.let { "  |  출발 전 예보: ${Forecast.NAMES[it.level]}" } ?: "")
        diaryNext.text = "다음엔: " + nextAdvice(level, top, dur)
        show(Phase.DIARY)
        log("도착 — 일기: ${diaryHeadline.text} / ${diaryNext.text} / ${diaryStats.text}")
        val summary = File(csv?.parentFile, (csv?.nameWithoutExtension ?: "trip") + "_summary.txt")
        summary.writeText(listOf(diaryHeadline.text, diaryTop.text, diaryNext.text, diaryStats.text, "", "목소리: ${voice.history}", "", "기록:").joinToString("\n") + "\n" + tripLog.joinToString("\n") + "\n")
    }

    private fun nextAdvice(level: Int, top: List<ShakeEngine.Window>, dur: Double): String = when {
        engine.stopGoPer10Min() >= 3.0 && dur > 120 -> "30분 일찍 출발하면 가다 서다가 줄어요."
        level >= 2 && top.firstOrNull()?.lateral == true -> "굽은 길이 시작되기 전에 쉬는 곳에 한 번 들러요."
        level >= 2 -> "가다 서다 구간 앞에서 쉬는 곳에 한 번 들러요."
        dur > 3600 -> "중간에 쉬는 곳을 한 번 넣어 보세요."
        else -> "오늘처럼 가면 돼요."
    }

    private fun fmt(sec: Double): String { val s = sec.toInt(); return "%d:%02d".format(s / 60, s % 60) }

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
                // ask again so we only use values that belong to this route (older pushes can arrive late)
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
            log("navi TBT ${list.size}건, 다음 ${list.firstOrNull()?.distance} m ${list.firstOrNull()?.description}")
            maybeForecast()
        }
        override fun onRouteStateInfo(info: RouteStateInfo) {
            val ts = info.trafficStatus
            log("navi routeState fresh=${fresh()} congestion=${ts?.summary?.totalCongestionDist} items=${ts?.congestionItemList?.size} roads=${ts?.congestionItemList?.take(4)?.joinToString { "${it.roadName}@${it.offset.toInt()}" }}")
            if (!fresh()) return
            routeState = info; maybeForecast()
        }
        override fun onDestinationInfo(info: DestinationInfo) { log("navi destination ${info.name} ${info.remainDistance} m ${info.remainTime} s") }
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

    /** NaviHelper.requestRoute opens the navigation app on top — bring our task back. */
    private fun bringToFront() {
        try {
            getSystemService(ActivityManager::class.java).moveTaskToFront(taskId, 0)
            log("moveTaskToFront called")
        } catch (e: Throwable) { log("moveTaskToFront failed $e") }
    }

    // =====================================================================
    // Pleos Vehicle — 조향 · 방향지시등 · 브레이크 · 뒷좌석 · 문
    private val seatAreas = linkedMapOf("왼쪽" to SeatValue.ROW_2_LEFT, "가운데" to SeatValue.ROW_2_CENTER, "오른쪽" to SeatValue.ROW_2_RIGHT)
    private val doorAreas = linkedMapOf("앞좌" to DoorValue.ROW_1_LEFT, "앞우" to DoorValue.ROW_1_RIGHT, "뒤좌" to DoorValue.ROW_2_LEFT, "뒤우" to DoorValue.ROW_2_RIGHT)
    private val seatListeners = HashMap<String, SeatOccupancyListener>()
    private val doorListeners = HashMap<String, DoorPositionListener>()

    private fun onSeat(k: String, v: Boolean?) { if (seatRaw[k] != v) log("seat $k raw=$v → ${if (v == null) "?" else if ((if (invertSeat) !v else v)) "앉음" else "빔"}"); seatRaw[k] = v }
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
        override fun onChanged(data: LocationData?) { if (data != null) { lat = data.latitude; lon = data.longitude } }
        override fun onError(exception: Exception) { log("fused error ${exception.message}") }
    }

    private fun startFused() {
        try { fused = FusedLocation(applicationContext).also { it.initialize() }; main.postDelayed({ registerFused() }, 2500) }
        catch (e: Throwable) { log("fused init threw $e") }
    }

    private fun registerFused() {
        val f = fused ?: return
        try { f.unregisterFusedLocationCallback(fusedListener) } catch (_: Throwable) {}
        try { f.registerFusedLocationCallback(fusedListener) } catch (e: Throwable) { log("fused register failed ${e.message}") }
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); log("onNewIntent") }

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
        private val DESTINATIONS = listOf(
            Triple("판교역", 37.394761, 127.111217),
            Triple("서울역", 37.554648, 126.970702),
            Triple("강릉역", 37.764067, 128.899536),
        )
        private val REVIEW = arrayOf(
            "편안하게 왔어요",
            "굽은 길에서 살짝 흔들렸어요",
            "가다 서다와 굽은 길이 겹쳐 울렁였어요",
            "많이 흔들린 날이에요, 고생했어요",
        )
    }
}
