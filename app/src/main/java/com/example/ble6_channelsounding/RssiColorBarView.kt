package com.example.ble6_channelsounding

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** Connection RSSI only; values outside the display range are clamped visually. */
class RssiColorBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rssi: Int? = null
    private fun dp(value: Float) = value * resources.displayMetrics.density

    fun setRssi(value: Int?) {
        rssi = value
        contentDescription = value?.let { "Connection RSSI $it dBm, 표시 범위 -100~-20 dBm" }
            ?: "Connection RSSI 없음"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = dp(32f)
        val bottom = height - dp(22f)
        if (bottom <= top) return
        val left = dp(12f)
        val right = dp(36f)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(23, 43, 58)
        paint.textSize = dp(12f)
        canvas.drawText("RSSI (dBm)", dp(5f), dp(17f), paint)
        paint.color = Color.rgb(225, 231, 235)
        canvas.drawRect(left, top, right, bottom, paint)
        paint.shader = LinearGradient(0f, top, 0f, bottom,
            intArrayOf(Color.rgb(253, 231, 37), Color.rgb(94, 201, 98),
                Color.rgb(33, 145, 140), Color.rgb(59, 82, 139), Color.rgb(68, 1, 84)),
            null, Shader.TileMode.CLAMP)
        rssi?.let { value ->
            val level = top + (-20 - value.coerceIn(-100, -20)) / 80f * (bottom - top)
            canvas.drawRect(left, level, right, bottom, paint)
        }
        paint.shader = null
        paint.color = Color.rgb(23, 43, 58)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        canvas.drawRect(left, top, right, bottom, paint)
        paint.style = Paint.Style.FILL
        for (tick in listOf(-20, -40, -60, -80, -100)) {
            val y = top + (-20 - tick) / 80f * (bottom - top)
            canvas.drawLine(right, y, right + dp(5f), y, paint)
            canvas.drawText(tick.toString(), right + dp(18f),
                y - (paint.ascent() + paint.descent()) / 2, paint)
        }
        rssi?.let { value ->
            val y = top + (-20 - value.coerceIn(-100, -20)) / 80f * (bottom - top)
            paint.color = Color.WHITE
            paint.strokeWidth = dp(4f)
            canvas.drawLine(left, y, right, y, paint)
            paint.color = Color.rgb(23, 43, 58)
            paint.strokeWidth = dp(1.5f)
            canvas.drawLine(left, y, right, y, paint)
            val arrow = Path().apply {
                moveTo(right + dp(2f), y)
                lineTo(right + dp(12f), y - dp(5f))
                lineTo(right + dp(12f), y + dp(5f))
                close()
            }
            canvas.drawPath(arrow, paint)
        }
    }
}
