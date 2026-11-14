package com.recovlab.motionforecast

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 화면 공통 값 — 색 · 글꼴 · 단계별 물빛.
 * 겉모습 v4: 물결 예보 카드 + 색 칸 승차감 일기 (시안: 3. 개발/v4_시안_261111, 형님 선택 11-11)
 * 색 v5: 짜임은 그대로, 색만 파랑 한 가지 계열로 — 흰 하늘 · 파랑 바다 · 검정 글자 (시안: 3. 개발/v5_시안_261114 색 1, 형님 선택 11-14)
 */
object Ui {
    fun rgb(v: Int): Int = (0xFF shl 24) or v

    val INK = rgb(0x0A0B0D)
    val SUB = rgb(0x5B616E)
    val PAPER = rgb(0xF5F7FA)
    val FAINT = rgb(0xA8B3C7)
    val SUN = rgb(0xF4B000)
    /** 하나뿐인 강조 색 — 울렁 단계의 물빛이자 '켜기' 단추 */
    val BLUE = rgb(0x0052FF)

    /** 하늘 2색 + 물결 3겹 + 깊은 물 */
    class Pal(val sky1: Int, val sky2: Int, val w1: Int, val w2: Int, val w3: Int, val deep: Int)

    /** 0 맑음 · 1 구름 · 2 울렁 · 3 출렁 — 단계가 오를수록 파랑이 짙어짐 (구름만 회청색) */
    val PAL = arrayOf(
        Pal(rgb(0xDDEAFF), rgb(0xFFFFFF), rgb(0xCFE0FF), rgb(0x8FB6FF), rgb(0x4D8DFF), rgb(0x2F6FEB)),
        Pal(rgb(0xE6E9EF), rgb(0xFFFFFF), rgb(0xD3D9E4), rgb(0xA1AEC6), rgb(0x687A9A), rgb(0x4F6080)),
        Pal(rgb(0xDCE7FF), rgb(0xFFFFFF), rgb(0xB9CFFF), rgb(0x5C8FFF), rgb(0x0052FF), rgb(0x003ECC)),
        Pal(rgb(0xDCE2EF), rgb(0xFAFBFD), rgb(0xA9B8D6), rgb(0x3F63B8), rgb(0x0A2A8C), rgb(0x061A5C)),
    )

    /** 예보가 아직 없을 때 (목적지 고르기 전 · 주행 중 대기 화면) */
    val IDLE = Pal(rgb(0xE3EBF8), rgb(0xFFFFFF), rgb(0xCBD9F2), rgb(0x97B2E2), rgb(0x5B84CC), rgb(0x4169B3))

    /** 단계별 물결 높이 */
    val AMP = floatArrayOf(3f, 10f, 22f, 40f)

    /** 일기의 색 칸 — 흔들릴수록 진하게 */
    val TILE = intArrayOf(rgb(0xDCE8FF), rgb(0xA9C4FF), rgb(0x3D7BFF), rgb(0x0A2A8C))

    private val faces = HashMap<Int, Typeface>()

    /** Noto Sans KR (assets/fonts, 굵기 100~900) */
    fun face(ctx: Context, weight: Int): Typeface = faces.getOrPut(weight) {
        try {
            Typeface.Builder(ctx.assets, "fonts/NotoSansKR-VF.ttf").setFontVariationSettings("'wght' $weight").build()
        } catch (t: Throwable) {
            Typeface.create(Typeface.DEFAULT, weight, false)
        }
    }
}

/**
 * 설계 좌표 1680 × 1208 (Pleos Connect 앱 칸)에 그리고 실제 크기로 늘림.
 * 아래쪽 요소는 화면 높이에 맞춰 내려감(dy). 누를 수 있는 곳은 그릴 때 hit()로 등록.
 */
abstract class SceneView(context: Context) : View(context) {
    protected val p = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val tp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    protected var vh = 1208f
    protected val dy: Float get() = vh - 1208f
    private var k = 1f
    private class Hit(val r: RectF, val f: () -> Unit)
    private val hits = ArrayList<Hit>()
    private val t0 = SystemClock.uptimeMillis()
    protected val tSec: Float get() = (SystemClock.uptimeMillis() - t0) / 1000f
    private val wavePath = Path()
    private val tmp = RectF()

    /** 물결을 흘릴지 — 주행 중에는 차가 화면을 가리므로 멈춘다 (화면 그리기가 주행 기록 주기를 밀지 않게) */
    var animate = true
        set(v) { field = v; if (v) invalidate() }

    init { isClickable = true }

    abstract fun scene(c: Canvas)

    final override fun onDraw(c: Canvas) {
        k = width / W
        vh = if (k > 0f) height / k else 1208f
        hits.clear()
        c.save(); c.scale(k, k)
        scene(c)
        c.restore()
        if (isShown && animate) postInvalidateDelayed(40)   // 물결이 천천히 흐름 — 화면이 보일 때만
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && k > 0f) {
            val x = e.x / k; val y = e.y / k
            val h = hits.lastOrNull { it.r.contains(x, y) }
            if (h != null) { performClick(); h.f(); invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    protected fun hit(l: Float, t: Float, r: Float, b: Float, f: () -> Unit) { hits.add(Hit(RectF(l, t, r, b), f)) }

    // ---------------------------------------------------------------- 글자
    private fun font(size: Float, weight: Int, color: Int, align: Paint.Align, ls: Float) {
        tp.typeface = Ui.face(context, weight); tp.textSize = size; tp.color = color; tp.textAlign = align; tp.letterSpacing = ls
    }

    protected fun tw(s: String, size: Float, weight: Int, ls: Float = LS): Float { font(size, weight, Ui.INK, Paint.Align.LEFT, ls); return tp.measureText(s) }

    /** 글자 상자의 위(top)와 높이(boxH) 기준으로 그림 — 웹 시안의 줄 상자와 같은 자리. 그린 폭을 돌려줌 */
    protected fun text(c: Canvas, s: String, x: Float, top: Float, size: Float, weight: Int, color: Int,
                       align: Paint.Align = Paint.Align.LEFT, boxH: Float = size * LINE, ls: Float = LS): Float {
        font(size, weight, color, align, ls)
        c.drawText(s, x, top + (boxH - LINE * size) / 2f + ASCENT * size, tp)
        return tp.measureText(s)
    }

    /** 폭에 맞춰 글자를 줄여 그림 (minSize까지). 그래도 넘치면 끝을 …로. 그린 폭을 돌려줌 */
    protected fun textFit(c: Canvas, s: String, x: Float, top: Float, boxH: Float, size: Float, minSize: Float, weight: Int, color: Int,
                          maxW: Float, align: Paint.Align = Paint.Align.LEFT, ls: Float = LS): Float {
        var sz = size; var str = s
        var w = tw(str, sz, weight, ls)
        if (w > maxW) { sz = max(minSize, sz * maxW / w); w = tw(str, sz, weight, ls) }
        while (w > maxW && str.length > 2) { str = str.dropLast(2) + "…"; w = tw(str, sz, weight, ls) }
        text(c, str, x, top, sz, weight, color, align, boxH, ls)
        return w
    }

    // ---------------------------------------------------------------- 도형
    protected fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = color
        tmp.set(l, t, r, b); c.drawRoundRect(tmp, rad, rad, p)
    }

    protected fun dot(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = color; c.drawCircle(cx, cy, r, p)
    }

    protected fun alpha(color: Int, a: Float): Int = Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private var skyFor: Ui.Pal? = null; private var skyH = 0f; private var skyShader: Shader? = null
    protected fun sky(c: Canvas, pal: Ui.Pal) {
        if (skyFor !== pal || skyH != vh) { skyFor = pal; skyH = vh; skyShader = LinearGradient(0f, 0f, 0f, vh * 0.62f, pal.sky1, pal.sky2, Shader.TileMode.CLAMP) }
        p.style = Paint.Style.FILL; p.shader = skyShader
        c.drawRect(0f, 0f, W, vh, p); p.shader = null
    }

    private var seaFor: Ui.Pal? = null; private var seaTop = 0f; private var seaShader: Shader? = null
    /** 물결 3겹. amp = 물결 높이(단계가 높을수록 큼), speed = 흐르는 빠르기 */
    protected fun sea(c: Canvas, top: Float, pal: Ui.Pal, amp: Float, speed: Float) {
        if (seaFor !== pal || seaTop != top) { seaFor = pal; seaTop = top; seaShader = LinearGradient(0f, top + 90f, 0f, vh, pal.w3, pal.deep, Shader.TileMode.CLAMP) }
        val t = tSec * speed
        wave(c, top + 52f, amp, 360f, 0.6f + t * 0.30f, pal.w1, null)
        wave(c, top + 84f, amp * 0.9f, 300f, 2.4f - t * 0.22f, pal.w2, null)
        wave(c, top + 120f, amp * 0.8f, 250f, 4.4f + t * 0.38f, 0, seaShader)
    }

    private fun wave(c: Canvas, base: Float, amp: Float, len: Float, phase: Float, color: Int, shader: Shader?) {
        val w = wavePath; w.rewind()
        w.moveTo(0f, vh + 2f); w.lineTo(0f, base + amp * sin(phase))
        var x = 0f
        while (x <= W) { w.lineTo(x, base + amp * sin(2f * PI.toFloat() * x / len + phase)); x += 8f }
        w.lineTo(W, vh + 2f); w.close()
        p.style = Paint.Style.FILL; p.shader = shader; if (shader == null) p.color = color
        c.drawPath(w, p); p.shader = null
    }

    protected fun sun(c: Canvas, x: Float, y: Float, size: Float, color: Int) {
        val s = size / 200f
        c.save(); c.translate(x, y); c.scale(s, s)
        p.shader = null; p.color = color; p.style = Paint.Style.FILL
        c.drawCircle(100f, 100f, 40f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 12f; p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) {
            val a = i * PI / 4
            c.drawLine(100f + 60f * cos(a).toFloat(), 100f + 60f * sin(a).toFloat(), 100f + 82f * cos(a).toFloat(), 100f + 82f * sin(a).toFloat(), p)
        }
        p.style = Paint.Style.FILL
        c.restore()
    }

    /** 구름 (원본 200 × 130) */
    protected fun cloud(c: Canvas, x: Float, y: Float, scale: Float, color: Int) {
        c.save(); c.translate(x, y); c.scale(scale, scale)
        p.shader = null; p.style = Paint.Style.FILL; p.color = color
        c.drawPath(CLOUD, p)
        c.restore()
    }

    /** 작은 물결 줄 (범례 · 단계 그림) */
    protected fun squiggle(c: Canvas, x: Float, y: Float, width: Float, amp: Float, len: Float, stroke: Float, color: Int) {
        val w = wavePath; w.rewind()
        var xx = 0f
        while (xx <= width) {
            val yy = y + amp * sin(2f * PI.toFloat() * (xx + 4f) / len)
            if (xx == 0f) w.moveTo(x + xx, yy) else w.lineTo(x + xx, yy)
            xx += 2f
        }
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = stroke; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.color = color
        c.drawPath(w, p); p.style = Paint.Style.FILL
    }

    /** 단계 그림 (원본 200 × 200): 해 · 구름 · 구름과 잔물결 · 큰 물결 */
    protected fun levelIcon(c: Canvas, level: Int, x: Float, y: Float, size: Float) {
        val s = size / 200f
        when (level) {
            0 -> sun(c, x, y, size, Ui.SUN)
            1 -> cloud(c, x, y + 38f * s, s, rgb(0xB8C2D6))
            2 -> {
                cloud(c, x + 12f * s, y + 2f * s, 0.88f * s, rgb(0xB8C2D6))
                squiggle(c, x + 16f * s, y + 142f * s, 168f * s, -9f * s, 84f * s, 11f * s, Ui.BLUE)
                squiggle(c, x + 16f * s, y + 174f * s, 168f * s, -9f * s, 84f * s, 11f * s, rgb(0x7FA8FF))
            }
            else -> {
                squiggle(c, x + 16f * s, y + 58f * s, 168f * s, -17f * s, 84f * s, 15f * s, rgb(0x0A2A8C))
                squiggle(c, x + 16f * s, y + 102f * s, 168f * s, -17f * s, 84f * s, 15f * s, Ui.BLUE)
                squiggle(c, x + 16f * s, y + 146f * s, 168f * s, -17f * s, 84f * s, 15f * s, rgb(0x7FA8FF))
            }
        }
    }

    protected fun rgb(v: Int) = Ui.rgb(v)

    /** 위쪽 줄: 왼쪽 제목 */
    protected fun title(c: Canvas, s: String) { text(c, s, 72f, 30f, 40f, 700, Ui.INK) }

    companion object {
        const val W = 1680f
        const val LINE = 1.448f     // Noto Sans KR 줄 높이 (글자 크기 대비)
        const val ASCENT = 1.16f    // 줄 상자 위에서 글자 밑줄까지
        const val LS = -0.01f
        val CLOUD = Path().apply {
            moveTo(54f, 118f)
            cubicTo(28f, 118f, 12f, 100f, 12f, 80f)
            cubicTo(12f, 60f, 28f, 44f, 49f, 44f)
            cubicTo(55f, 22f, 76f, 8f, 100f, 8f)
            cubicTo(129f, 8f, 152f, 28f, 156f, 55f)
            cubicTo(175f, 57f, 190f, 72f, 190f, 90f)
            cubicTo(190f, 107f, 177f, 118f, 160f, 118f)
            close()
        }
    }
}

/** ① 출발 전 — 흔들림 예보 카드: 화면 아래 바다의 물빛과 물결 높이가 곧 예보 */
class PlanScene(context: Context) : SceneView(context) {
    var forecast: Forecast? = null
    var destName = ""
    var busy = false                 // 경로를 받는 중
    var error: String? = null
    var passenger: Passenger? = null
    var voiceOn = false
    var destinations: List<String> = emptyList()
    var menuOpen = false
    var askVoice = false
    /** 개발용 빌드에서만 쓰는 값: 단계별 모습 보기 (0~3). 숫자는 단계마다 달라서 가림 */
    var lookLevel: Int? = null
    var onPickDest: (Int) -> Unit = {}
    var onPassenger: (Passenger) -> Unit = {}
    var onVoice: (Boolean) -> Unit = {}
    var onAskVoice: (Boolean) -> Unit = {}
    var onSettings: () -> Unit = {}

    override fun scene(c: Canvas) {
        val f = forecast
        val level = if (f != null) lookLevel ?: f.level else null
        val pal = if (level != null) Ui.PAL[level] else Ui.IDLE
        sky(c, pal)

        // 하늘: 맑음은 해, 나머지는 구름 (천천히 떠다님)
        if (level == 0) sun(c, 1260f, 150f, 270f, Ui.SUN)
        else {
            val drift = 8f * sin(tSec * 0.25f)
            cloud(c, 1200f + drift, 164f, 1.65f, alpha(Ui.INK, 0.06f))
            cloud(c, 1200f + drift, 150f, 1.65f, if (level == 3) rgb(0xC5CEE0) else Color.WHITE)
        }

        // 네 단계 — 물결 크기로 보는 범례 (위가 출렁)
        for (i in 3 downTo 0) {
            val y = 420f + (3 - i) * 60f; val on = i == level
            squiggle(c, 1282f, y + 30f, 88f, LEGEND_AMP[i], 44f, if (on) 8f else 6f, if (on) pal.w3 else Ui.FAINT)
            text(c, Forecast.NAMES[i], 1394f, y, 30f, if (on) 800 else 500, if (on) Ui.INK else Ui.SUB, boxH = 60f)
        }

        // 큰 글자
        if (f != null && level != null) {
            text(c, "${f.destination} 가는 길, 오늘은", 72f, 132f, 40f, 500, Ui.SUB)
            text(c, Forecast.NAMES[level], 64f, 190f, 252f, 800, Ui.INK, boxH = 257f, ls = -0.04f)
            text(c, Forecast.PLAN_SAY[level], 72f, 451f, 46f, 600, Ui.INK)
            // 근거 세 가지
            var x = if (lookLevel == null) 72f else 9999f
            val h = f.durationSec / 3600; val m = (f.durationSec % 3600) / 60
            val time = if (h > 0 && m > 0) "${h}시간 $m" to "분" else if (h > 0) "$h" to "시간" else "$m" to "분"
            for ((label, num, unit) in listOf(Triple("굽은 길", "${f.turns}", "번"), Triple("가다 서다", "${f.stopGoSections}", "구간"), Triple("걸리는 시간", time.first, time.second))) {
                val lw = text(c, label, x, 572f, 30f, 500, Ui.SUB)
                val nw = text(c, num, x, 616f, 72f, 700, Ui.INK, ls = -0.03f)
                val uw = text(c, unit, x + nw + 6f, 658f, 36f, 600, Ui.INK)
                x += max(lw, nw + 6f + uw) + 64f
            }
        } else {
            text(c, if (busy) "$destName 가는 길" else "오늘 길 흔들림을 날씨처럼", 72f, 132f, 40f, 500, Ui.SUB)
            text(c, if (busy) "살펴보는 중" else "어디로 가세요?", 66f, 210f, 150f, 800, Ui.INK, boxH = 170f, ls = -0.04f)
            val say = error ?: if (busy) "잠시만요, 길을 읽고 있어요." else "위에서 목적지를 고르면 바로 알려 드려요."
            textFit(c, say, 72f, 400f, 66f, 46f, 30f, 600, Ui.INK, 1100f)
        }

        sea(c, vh - 495f, pal, if (level != null) Ui.AMP[level] else 6f, 1f + (level ?: 0) * 0.3f)

        // 준비 3가지 (물 위의 흰 카드)
        if (f != null && level != null) {
            val head = when (passenger) { Passenger.CHILD -> "아이와 함께라면"; Passenger.ADULT -> "어른과 함께라면"; Passenger.SENIOR -> "어르신과 함께라면"; null -> "오늘 챙길 것" }
            text(c, head, 72f, 846f + dy, 30f, 700, Color.WHITE)
            val cw = (1536f - 40f) / 3f
            Forecast.prep(level, passenger).forEachIndexed { i, s ->
                val l = 72f + i * (cw + 20f)
                rr(c, l, 900f + dy, l + cw, 1004f + dy, 26f, alpha(Color.WHITE, 0.95f))
                textFit(c, s, l + 32f, 900f + dy, 104f, 34f, 26f, 600, Ui.INK, cw - 64f)
            }
        }

        // 탑승자 · 목소리 안내
        val fy = 1078f + dy
        val lw = text(c, "탑승자", 72f, fy, 32f, 500, Color.WHITE, boxH = 80f)
        var sx = 72f + lw + 22f
        val segs = Passenger.values().map { it to tw(it.label, 32f, 500) + 80f }
        rr(c, sx, fy, sx + segs.sumOf { it.second.toDouble() }.toFloat() + 12f, fy + 80f, 40f, alpha(Color.WHITE, 0.2f))
        sx += 6f
        for ((who, w) in segs) {
            val on = who == passenger
            if (on) rr(c, sx, fy + 6f, sx + w, fy + 74f, 34f, Color.WHITE)
            text(c, who.label, sx + w / 2f, fy + 6f, 32f, if (on) 700 else 500, if (on) Ui.INK else Color.WHITE, Paint.Align.CENTER, 68f)
            hit(sx, fy - 8f, sx + w, fy + 88f) { onPassenger(who) }
            sx += w
        }
        text(c, "목소리 안내", 1474f, fy + 8f, 32f, 500, Color.WHITE, Paint.Align.RIGHT, 64f)
        rr(c, 1496f, fy + 8f, 1608f, fy + 72f, 32f, if (voiceOn) Color.WHITE else alpha(Color.WHITE, 0.3f))
        dot(c, if (voiceOn) 1576f else 1528f, fy + 40f, 26f, if (voiceOn) pal.w3 else Color.WHITE)
        hit(1300f, fy - 8f, 1620f, fy + 88f) { onVoice(!voiceOn) }

        // 위쪽 줄: 제목 · 목적지 · 설정
        title(c, "승차감·흔들림 예보")
        val name = destName.ifEmpty { "고르기" }
        val w1 = tw("목적지", 32f, 500); val w2 = tw(name, 32f, 700)
        val pw = 30f + w1 + 14f + w2 + 14f + 22f + 30f
        val px = 1512f - pw
        rr(c, px, 27f, 1512f, 95f, 34f, alpha(Ui.INK, 0.08f))
        rr(c, px, 25f, 1512f, 93f, 34f, Color.WHITE)
        text(c, "목적지", px + 30f, 25f, 32f, 500, Ui.SUB, boxH = 68f)
        text(c, name, px + 30f + w1 + 14f, 25f, 32f, 700, Ui.INK, boxH = 68f)
        caret(c, 1512f - 30f - 11f, 61f, menuOpen)
        hit(px - 10f, 10f, 1522f, 108f) { menuOpen = !menuOpen }
        for (i in 0..2) dot(c, 1574f, 46f + i * 13f, 3.6f, Ui.SUB)
        hit(1530f, 10f, 1630f, 108f) { onSettings() }

        if (menuOpen) menu(c, min(px, 1512f - 340f))
        if (askVoice) ask(c)
    }

    private val tri = Path()
    private fun caret(c: Canvas, cx: Float, cy: Float, up: Boolean) {
        val d = if (up) -1f else 1f
        tri.rewind(); tri.moveTo(cx - 9f, cy - 5f * d); tri.lineTo(cx + 9f, cy - 5f * d); tri.lineTo(cx, cy + 7f * d); tri.close()
        p.shader = null; p.style = Paint.Style.FILL; p.color = Ui.SUB; c.drawPath(tri, p)
    }

    /** 목적지 고르기 (시험용 목적지 — 실제 차에서는 내비 목적지를 그대로 받음) */
    private fun menu(c: Canvas, left: Float) {
        hit(0f, 0f, W, vh) { menuOpen = false }
        val top = 107f; val rowH = 88f; val h = destinations.size * rowH + 24f
        rr(c, left, top + 4f, 1512f, top + h + 4f, 30f, alpha(Ui.INK, 0.10f))
        rr(c, left, top, 1512f, top + h, 30f, Color.WHITE)
        destinations.forEachIndexed { i, d ->
            val y = top + 12f + i * rowH; val on = d == destName
            if (on) dot(c, left + 40f, y + rowH / 2f, 7f, Ui.INK)
            text(c, d, left + 68f, y, 34f, if (on) 800 else 500, Ui.INK, boxH = rowH)
            hit(left, y, 1512f, y + rowH) { menuOpen = false; onPickDest(i) }
        }
    }

    /** "목소리 안내를 켤까요?" — 탑승자를 처음 고를 때 한 번만 */
    private fun ask(c: Canvas) {
        hit(0f, 0f, W, vh) { }
        p.shader = null; p.style = Paint.Style.FILL; p.color = alpha(Ui.INK, 0.45f); c.drawRect(0f, 0f, W, vh, p)
        val l = 400f; val t = vh / 2f - 220f; val r = 1280f; val b = t + 420f
        rr(c, l, t, r, b, 40f, Color.WHITE)
        text(c, "목소리 안내를 켤까요?", l + 56f, t + 48f, 52f, 800, Ui.INK, ls = -0.02f)
        text(c, "달리는 동안 굽은 길 앞에서 짧게 알려 드려요.", l + 56f, t + 142f, 34f, 500, Ui.SUB)
        text(c, "차 안 모두에게 들려요.", l + 56f, t + 192f, 34f, 500, Ui.SUB)
        val by = b - 56f - 92f
        val yesL = r - 56f - 240f; val noL = yesL - 24f - 240f
        rr(c, yesL, by, yesL + 240f, by + 92f, 46f, Ui.BLUE)
        text(c, "켜기", yesL + 120f, by, 36f, 700, Color.WHITE, Paint.Align.CENTER, 92f)
        hit(yesL, by, yesL + 240f, by + 92f) { askVoice = false; onAskVoice(true) }
        rr(c, noL, by, noL + 240f, by + 92f, 46f, rgb(0xEEF0F3))
        text(c, "나중에", noL + 120f, by, 36f, 600, Ui.INK, Paint.Align.CENTER, 92f)
        hit(noL, by, noL + 240f, by + 92f) { askVoice = false; onAskVoice(false) }
    }

    companion object {
        private val LEGEND_AMP = floatArrayOf(1f, 5f, 10f, 16f)
    }
}

/** ② 주행 중 · 도착 확인 중 — 주행 중 화면은 차가 가리고, P에 두면 이 화면이 잠깐 보임 */
class WaitScene(context: Context) : SceneView(context) {
    var arriving = false
    var level: Int? = null

    override fun scene(c: Canvas) {
        val lv = level
        val pal = if (lv != null) Ui.PAL[lv] else Ui.IDLE
        sky(c, pal)
        cloud(c, 1200f + 8f * sin(tSec * 0.25f), 150f, 1.65f, Color.WHITE)
        title(c, "승차감·흔들림 예보")
        text(c, if (arriving) "도착 확인 중" else "주행 중", 66f, 210f, 150f, 800, Ui.INK, boxH = 170f, ls = -0.04f)
        text(c, if (arriving) "문을 열면 승차감 일기가 열려요." else "화면은 쉬고, 목소리로 챙겨요.", 72f, 400f, 46f, 600, Ui.INK)
        sea(c, vh - 495f, pal, if (lv != null) Ui.AMP[lv] * 0.6f else 6f, 1f)
    }
}

/** 승차감 일기에 그릴 값 */
class DiaryData(
    val headline: String,
    val fcLevel: Int?,                    // 출발 전 예보 단계 (없으면 null)
    val actual: Int,                      // 실제 단계
    val why: String,
    val top: List<Pair<String, String>>,  // 가장 흔들린 곳: (어디, "앞뒤로 출렁")
    val tiles: IntArray,                  // 색 칸 한 칸 = 한 구간의 단계
    val pins: List<Int>,                  // 가장 흔들린 곳이 든 칸
    val caption: String,                  // "출발부터 도착까지, 1분마다"
    val ticks: List<Pair<Float, String>>, // (0~1 자리, "10분")
    val next: String,
    val stats: List<Pair<String, String>>,
    val example: Boolean,
)

/** ③ 도착 후 — 승차감 일기: 하늘과 바다는 실제 단계의 색, 가운데는 구간별 색 칸 */
class DiaryScene(context: Context) : SceneView(context) {
    var data: DiaryData? = null
    var onNewTrip: () -> Unit = {}

    override fun scene(c: Canvas) {
        val d = data ?: run { sky(c, Ui.IDLE); return }
        val pal = Ui.PAL[d.actual]
        sky(c, pal)
        sea(c, vh - 405f, pal, Ui.AMP[d.actual], 1f + d.actual * 0.3f)

        // 위쪽 줄
        title(c, "승차감 일기")
        var right = 1608f
        if (d.example) {
            val bw = tw("예시 데이터", 28f, 500) + 36f
            rr(c, right - bw, 31f, right, 87f, 10f, Color.WHITE)
            text(c, "예시 데이터", right - bw + 18f, 31f, 28f, 500, Ui.SUB, boxH = 56f)
            right -= bw + 24f
        }
        text(c, "자체 기준으로 본 흔들림이에요 · 의학적 판정 아님", right, 31f, 26f, 500, Ui.SUB, Paint.Align.RIGHT, 56f)

        // 도착 한 줄 + 예보 → 실제
        textFit(c, d.headline, 72f, 126f, 90f, 62f, 44f, 800, Ui.INK, 980f, ls = -0.03f)
        var x = 72f
        if (d.fcLevel != null) { x = chip(c, x, 232f, d.fcLevel, "예보") + 18f; arrow(c, x, 268f); x += 40f + 18f }
        chip(c, x, 232f, d.actual, "실제")
        textFit(c, d.why, 72f, 328f, 52f, 36f, 28f, 500, Ui.INK, 980f)

        // 가장 흔들린 곳
        rr(c, 1088f, 126f, 1608f, 396f, 28f, alpha(Color.WHITE, 0.95f))
        text(c, "가장 흔들린 곳", 1118f, 148f, 28f, 500, Ui.SUB)
        if (d.top.isEmpty()) text(c, "흔들린 곳이 없었어요", 1118f, 254f, 32f, 500, Ui.SUB, boxH = 60f)
        d.top.forEachIndexed { i, (where, kind) ->
            val y = 194f + i * 60f
            dot(c, 1141f, y + 30f, 23f, Ui.INK)
            text(c, "${i + 1}", 1141f, y + 7f, 25f, 700, Color.WHITE, Paint.Align.CENTER, 46f)
            val room = 1578f - 1180f
            val full = tw(where, 32f, 500) + 12f + tw(kind, 32f, 700)
            val sz = if (full > room) max(24f, 32f * room / full) else 32f
            val kw = tw(kind, sz, 700)
            val ww = textFit(c, where, 1180f, y, 60f, sz, sz, 500, Ui.INK, room - kw - 12f)
            text(c, kind, 1180f + ww + 12f, y, sz, 700, Ui.INK, boxH = 60f)
        }

        // 색 칸: 출발부터 도착까지
        rr(c, 72f, 430f, 1608f, 760f, 28f, alpha(Color.WHITE, 0.95f))
        text(c, d.caption, 108f, 454f, 28f, 500, Ui.SUB)
        var kx = 1572f
        for (i in 3 downTo 0) {
            kx -= text(c, Forecast.NAMES[i], kx, 454f, 28f, 500, Ui.SUB, Paint.Align.RIGHT)
            kx -= 10f + 26f
            rr(c, kx, 461f, kx + 26f, 487f, 6f, Ui.TILE[i])
            kx -= 28f
        }
        val n = d.tiles.size
        if (n > 0) {
            val gap = if (n <= 45) 4f else 2f
            val tw1 = (1464f - gap * (n - 1)) / n
            for (i in 0 until n) {
                val l = 108f + i * (tw1 + gap)
                rr(c, l, 564f, l + tw1, 688f, min(6f, tw1 / 3f), Ui.TILE[d.tiles[i].coerceIn(0, 3)])
            }
            // 가장 흔들린 곳 번호 — 서로 겹치지 않게 조금 벌림
            val xs = d.pins.map { 108f + it.coerceIn(0, n - 1) * (tw1 + gap) + tw1 / 2f }.toFloatArray()
            val order = xs.indices.sortedBy { xs[it] }
            for (j in 1 until order.size) if (xs[order[j]] - xs[order[j - 1]] < 46f) xs[order[j]] = xs[order[j - 1]] + 46f
            for (i in xs.indices) {
                val cx = xs[i].coerceIn(108f + 21f, 1572f - 21f)
                rr(c, cx - 2f, 548f, cx + 2f, 562f, 0f, Ui.INK)
                dot(c, cx, 529f, 21f, Ui.INK)
                text(c, "${i + 1}", cx, 508f, 24f, 700, Color.WHITE, Paint.Align.CENTER, 42f)
            }
        }
        for ((frac, label) in d.ticks) {
            val tx = 108f + frac.coerceIn(0f, 1f) * 1464f
            val align = if (frac <= 0.01f) Paint.Align.LEFT else if (frac >= 0.985f) Paint.Align.RIGHT else Paint.Align.CENTER
            text(c, label, tx, 698f, 28f, 500, Ui.SUB, align)
        }

        // 다음엔 … (물속)
        text(c, "다음엔", 72f, 1003f + dy, 28f, 500, alpha(Color.WHITE, 0.85f))
        textFit(c, d.next, 72f, 1047f + dy, 61f, 42f, 30f, 700, Color.WHITE, 1260f)
        var sx = 72f
        for ((label, value) in d.stats) {
            sx += text(c, label, sx, 1124f + dy, 28f, 500, alpha(Color.WHITE, 0.92f)) + 10f
            sx += text(c, value, sx, 1124f + dy, 28f, 700, Color.WHITE) + 40f
        }
        rr(c, 1378f, 1076f + dy, 1608f, 1164f + dy, 44f, Color.WHITE)
        text(c, "새 여행", 1493f, 1076f + dy, 34f, 700, Ui.INK, Paint.Align.CENTER, 88f)
        hit(1360f, 1060f + dy, 1620f, 1180f + dy) { onNewTrip() }
    }

    /** 흰 알약: 단계 그림 + "예보"/"실제" + 단계 이름. 오른쪽 끝 x를 돌려줌 */
    private fun chip(c: Canvas, x: Float, y: Float, level: Int, label: String): Float {
        val lw = tw(label, 28f, 500); val nw = tw(Forecast.NAMES[level], 36f, 700)
        val w = 18f + 52f + 12f + lw + 12f + nw + 28f
        rr(c, x, y, x + w, y + 72f, 36f, Color.WHITE)
        levelIcon(c, level, x + 18f, y + 10f, 52f)
        text(c, label, x + 82f, y, 28f, 500, Ui.SUB, boxH = 72f)
        text(c, Forecast.NAMES[level], x + 82f + lw + 12f, y, 36f, 700, Ui.INK, boxH = 72f)
        return x + w
    }

    private fun arrow(c: Canvas, x: Float, cy: Float) {
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 3.5f; p.strokeCap = Paint.Cap.ROUND; p.color = Ui.SUB
        c.drawLine(x + 2f, cy, x + 36f, cy, p); c.drawLine(x + 36f, cy, x + 25f, cy - 10f, p); c.drawLine(x + 36f, cy, x + 25f, cy + 10f, p)
        p.style = Paint.Style.FILL
    }
}
