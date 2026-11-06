package com.recovlab.motionforecast

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

object Palette {
    val INK = Color.rgb(28, 30, 34)
    val MUTED = Color.rgb(110, 116, 124)
    val SUN = Color.rgb(245, 166, 35)
    val CLOUD = Color.rgb(176, 186, 198)
    val CLOUD_DARK = Color.rgb(96, 104, 118)
    val WAVE = Color.rgb(64, 150, 140)
    val BOLT = Color.rgb(250, 200, 40)
    val LEVEL = intArrayOf(Color.rgb(245, 166, 35), Color.rgb(140, 156, 176), Color.rgb(64, 150, 140), Color.rgb(84, 76, 140))
    val LEVEL_BG = intArrayOf(Color.rgb(254, 246, 230), Color.rgb(240, 243, 247), Color.rgb(232, 245, 243), Color.rgb(238, 236, 248))
    /** 그래프 단계 띠: 맑음 하늘색 → 구름 회색 → 울렁 연보라 → 폭풍 진회색 */
    val GRAPH_BAND = intArrayOf(Color.rgb(214, 236, 250), Color.rgb(226, 229, 234), Color.rgb(228, 218, 244), Color.rgb(110, 115, 124))
}

/** 날씨 아이콘 4개: 0 맑음 · 1 구름 · 2 울렁 · 3 폭풍 */
class WeatherIconView(context: Context) : View(context) {
    var level = 0
        set(v) { field = v.coerceIn(0, 3); invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        when (level) {
            0 -> sun(c, cx, cy, s * 0.22f)
            1 -> { sun(c, cx - s * 0.12f, cy - s * 0.10f, s * 0.15f); cloud(c, cx + s * 0.04f, cy + s * 0.08f, s * 0.30f, Palette.CLOUD) }
            2 -> { cloud(c, cx, cy - s * 0.08f, s * 0.30f, Palette.CLOUD); waves(c, cx, cy + s * 0.20f, s * 0.34f) }
            else -> { cloud(c, cx, cy - s * 0.08f, s * 0.32f, Palette.CLOUD_DARK); bolt(c, cx, cy + s * 0.02f, s * 0.30f) }
        }
    }

    private fun sun(c: Canvas, x: Float, y: Float, r: Float) {
        p.style = Paint.Style.FILL; p.color = Palette.SUN
        c.drawCircle(x, y, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.16f; p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) {
            val a = Math.PI * i / 4
            c.drawLine(x + (r * 1.35f * cos(a)).toFloat(), y + (r * 1.35f * sin(a)).toFloat(), x + (r * 1.75f * cos(a)).toFloat(), y + (r * 1.75f * sin(a)).toFloat(), p)
        }
    }

    private fun cloud(c: Canvas, x: Float, y: Float, w: Float, color: Int) {
        p.style = Paint.Style.FILL; p.color = color
        c.drawCircle(x - w * 0.45f, y + w * 0.10f, w * 0.38f, p)
        c.drawCircle(x, y - w * 0.12f, w * 0.52f, p)
        c.drawCircle(x + w * 0.48f, y + w * 0.12f, w * 0.36f, p)
        c.drawRoundRect(RectF(x - w * 0.85f, y + w * 0.05f, x + w * 0.85f, y + w * 0.48f), w * 0.22f, w * 0.22f, p)
    }

    private fun waves(c: Canvas, x: Float, y: Float, w: Float) {
        p.style = Paint.Style.STROKE; p.color = Palette.WAVE; p.strokeWidth = w * 0.09f; p.strokeCap = Paint.Cap.ROUND
        for (row in 0..1) {
            val path = Path(); val yy = y + row * w * 0.28f
            path.moveTo(x - w * 0.8f, yy)
            var xx = x - w * 0.8f; var up = true
            while (xx < x + w * 0.8f) { path.quadTo(xx + w * 0.2f, yy + (if (up) -w * 0.16f else w * 0.16f), xx + w * 0.4f, yy); xx += w * 0.4f; up = !up }
            c.drawPath(path, p)
        }
    }

    private fun bolt(c: Canvas, x: Float, y: Float, s: Float) {
        p.style = Paint.Style.FILL; p.color = Palette.BOLT
        val path = Path().apply {
            moveTo(x + s * 0.05f, y); lineTo(x - s * 0.25f, y + s * 0.55f); lineTo(x - s * 0.02f, y + s * 0.55f)
            lineTo(x - s * 0.15f, y + s * 1.0f); lineTo(x + s * 0.30f, y + s * 0.38f); lineTo(x + s * 0.06f, y + s * 0.38f); lineTo(x + s * 0.22f, y); close()
        }
        c.drawPath(path, p)
    }
}

/** 흔들림 그래프 1개: 시간축 · 단계 배경 띠 · 가장 흔들린 3구간 표시 */
class ShakeGraphView(context: Context) : View(context) {
    var points: List<Pair<Double, Double>> = emptyList()   // (tSec, rms)
    var top: List<ShakeEngine.Window> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val padL = 70f; val padR = 20f; val padT = 16f; val padB = 44f
        val w = width - padL - padR; val h = height - padT - padB
        val tMax = (points.lastOrNull()?.first ?: 1.0).coerceAtLeast(1.0)
        val yMax = 1.0   // fixed scale so the four bands stay readable; larger values are drawn at the top edge
        fun X(t: Double) = padL + (t / tMax * w).toFloat()
        fun Y(v: Double) = padT + h - (minOf(v, yMax) / yMax * h).toFloat()

        // level bands
        val limits = doubleArrayOf(0.0) + ShakeEngine.LEVEL_LIMITS + doubleArrayOf(yMax)
        for (i in 0 until 4) {
            p.style = Paint.Style.FILL; p.color = Palette.GRAPH_BAND[i]
            c.drawRect(padL, Y(minOf(limits[i + 1], yMax)), padL + w, Y(limits[i]), p)
            p.color = Palette.MUTED; p.textSize = 28f
            c.drawText(Forecast.NAMES[i], 8f, Y((limits[i] + minOf(limits[i + 1], yMax)) / 2) + 10f, p)
        }
        // line — white halo so it stays visible on the dark '폭풍' band
        if (points.size > 1) {
            val path = Path(); path.moveTo(X(points[0].first), Y(points[0].second))
            for (pt in points.drop(1)) path.lineTo(X(pt.first), Y(pt.second))
            p.style = Paint.Style.STROKE; p.strokeJoin = Paint.Join.ROUND; p.strokeCap = Paint.Cap.ROUND
            p.color = Color.WHITE; p.strokeWidth = 10f; c.drawPath(path, p)
            p.color = Palette.INK; p.strokeWidth = 4f; c.drawPath(path, p)
        }
        // top-3: number markers only, at the highest point inside each window
        top.forEachIndexed { i, win ->
            val peak = points.filter { it.first >= win.startSec - 1e-6 && it.first < win.endSec }.maxByOrNull { it.second }
                ?: ((win.startSec + win.endSec) / 2 to win.rms)
            val x = X(peak.first).coerceIn(padL + 24f, padL + w - 24f)
            val y = (Y(peak.second) - 34f).coerceIn(padT + 22f, padT + h - 22f)
            p.style = Paint.Style.FILL; p.color = Color.WHITE; c.drawCircle(x, y, 21f, p)
            p.style = Paint.Style.STROKE; p.color = Palette.INK; p.strokeWidth = 3f; c.drawCircle(x, y, 21f, p)
            p.style = Paint.Style.FILL; p.color = Palette.INK; p.textSize = 26f; p.isFakeBoldText = true; p.textAlign = Paint.Align.CENTER
            c.drawText("${i + 1}", x, y + 9f, p)
            p.isFakeBoldText = false; p.textAlign = Paint.Align.LEFT
        }
        // axis
        p.style = Paint.Style.FILL; p.color = Palette.MUTED; p.textSize = 28f
        val step = niceStep(tMax)
        var t = 0.0
        while (t <= tMax + 1e-6) { c.drawText(fmtT(t), X(t) - 18f, padT + h + 32f, p); t += step }
    }

    private fun niceStep(tMax: Double) = listOf(5.0, 10.0, 30.0, 60.0, 300.0, 600.0, 1800.0).firstOrNull { tMax / it <= 8 } ?: 3600.0
    private fun fmtT(t: Double): String { val s = t.toInt(); return if (s >= 60) "${s / 60}분" + (if (s % 60 != 0) "${s % 60}" else "") else "${s}초" }
}
