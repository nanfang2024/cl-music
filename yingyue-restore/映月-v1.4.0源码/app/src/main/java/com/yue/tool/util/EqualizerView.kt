package com.yue.tool.util

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.yue.tool.R

/**
 * 播放中的动态均衡器：3 根跳动的金色柱
 * 附加到窗口自动播放，移除自动停止
 */
class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.moonGold)
    }

    private val phase = FloatArray(3)
    private val animators = arrayOfNulls<ValueAnimator>(3)
    private var barWidth = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    private fun startAnim() {
        if (animators[0] != null) return
        for (i in 0..2) {
            animators[i] = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (380 + i * 90).toLong()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase[i] = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    private fun stopAnim() {
        animators.forEach { it?.cancel() }
        animators.fill(null)
    }

    override fun onDraw(canvas: Canvas) {
        if (barWidth == 0f) barWidth = width / 7f
        for (i in 0..2) {
            val h = (height * (0.3f + 0.65f * phase[i]))
            val left = (width / 2f) + (i - 1) * barWidth * 2.2f - barWidth / 2f
            canvas.drawRoundRect(
                left,
                (height - h) / 2f,
                left + barWidth,
                (height + h) / 2f,
                barWidth / 2f,
                barWidth / 2f,
                paint
            )
        }
    }
}
