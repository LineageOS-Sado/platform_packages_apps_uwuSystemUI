/*
 * Copyright (C) 2026 The LineageOS-Sado Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.uwuaosp.systemui.volume

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import com.android.systemui.plugins.VolumeDialogController
import org.uwuaosp.systemui.moment.arc.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class VolumePanelView(context: Context) : View(context) {
    interface Listener {
        fun setVolume(stream: Int, level: Int)
        fun toggleMute(stream: Int, restoreLevel: Int)
        fun toggleExpanded()
        fun onInteraction()
        fun dismiss()
    }

    var listener: Listener? = null
    var expansion: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
            requestLayout()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val lastNonZero = mutableMapOf<Int, Int>()
    private var state: VolumeDialogController.State? = null
    private var expanded = false
    private var pressedStream = INVALID_STREAM
    private var pressedTopButton = false
    private var pressedIcon = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private val cardBounds = RectF()
    private val internalInsetsListener =
        ViewTreeObserver.OnComputeInternalInsetsListener { info ->
            updateCardBounds()
            info.setTouchableInsets(
                ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION,
            )
            info.touchableRegion.set(
                cardBounds.left.roundToInt(),
                cardBounds.top.roundToInt(),
                cardBounds.right.roundToInt(),
                cardBounds.bottom.roundToInt(),
            )
        }

    private val density = resources.displayMetrics.density
    private val night: Boolean
        get() =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    private val backgroundColor: Int
        get() = systemColor(
            if (night) android.R.color.system_neutral1_800
            else android.R.color.system_neutral1_0,
            if (night) 0xff25232a.toInt() else Color.WHITE,
        )
    private val activeColor: Int
        get() = systemColor(
            if (night) android.R.color.system_accent1_200
            else android.R.color.system_accent1_700,
            if (night) 0xffc7d7ff.toInt() else 0xff006782.toInt(),
        )
    private val inactiveColor: Int
        get() = systemColor(
            if (night) android.R.color.system_accent1_700
            else android.R.color.system_accent1_100,
            if (night) 0xff33434d.toInt() else 0xffd0e6f3.toInt(),
        )

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.volume_panel_description)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnComputeInternalInsetsListener(internalInsetsListener)
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnComputeInternalInsetsListener(internalInsetsListener)
        }
        super.onDetachedFromWindow()
    }

    fun updateState(state: VolumeDialogController.State?, expanded: Boolean) {
        this.state = state?.copy()
        this.expanded = expanded
        state?.states?.let { states ->
            for (i in 0 until states.size()) {
                val stream = states.keyAt(i)
                val streamState = states.valueAt(i)
                if (streamState.level > streamState.levelMin) {
                    lastNonZero[stream] = streamState.level
                }
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateCardBounds()
        paint.color = backgroundColor
        paint.style = Paint.Style.FILL
        val cardRadius = scaled(lerp(18f, 25f, expansion))
        canvas.drawRoundRect(cardBounds, cardRadius, cardRadius, paint)

        drawTuneButton(canvas)
        val streams = visibleStreams()
        if (streams.isEmpty()) return
        streams.forEachIndexed { index, stream ->
            val alpha =
                if (streams.size == 1) 1f
                else if (index == MEDIA_STREAM_INDEX) 1f
                else expansion
            val targetRightInset = EXPANDED_STREAM_RIGHT_INSETS[index.coerceAtMost(2)]
            val rightInset = lerp(FIXED_RIGHT_INSET, targetRightInset, expansion)
            val centerX = width - scaled(rightInset)
            drawStream(canvas, stream, centerX, alpha)
        }
    }

    private fun drawTuneButton(canvas: Canvas) {
        val centerX = width - scaled(FIXED_RIGHT_INSET)
        val centerY = scaled(22f)
        paint.color = activeColor
        paint.strokeWidth = scaled(1.4f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        for (i in 0..2) {
            val y = centerY - scaled(3f) + scaled(3f) * i
            canvas.drawLine(centerX - scaled(4f), y, centerX + scaled(4f), y, paint)
            val knob = if (i == 1) centerX - scaled(2f) else centerX + scaled(2f)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(knob, y, scaled(1.2f), paint)
            paint.style = Paint.Style.STROKE
        }
    }

    private fun drawStream(
        canvas: Canvas,
        stream: Int,
        centerX: Float,
        alpha: Float,
    ) {
        val streamState = state?.states?.get(stream) ?: return
        val disabled = isDisabled(stream)
        val visualAlpha = alpha * if (disabled) 0.35f else 1f
        val trackTop = scaled(46.6f)
        val trackBottom = scaled(186.1f)
        val range = max(1, streamState.levelMax - streamState.levelMin)
        val fraction =
            ((streamState.level - streamState.levelMin).toFloat() / range).coerceIn(0f, 1f)
        val levelY = trackBottom - (trackBottom - trackTop) * fraction

        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = scaled(3f)
        paint.alpha = (255 * visualAlpha).roundToInt()
        paint.color = inactiveColor
        canvas.drawLine(centerX, trackTop, centerX, trackBottom, paint)
        paint.color = activeColor
        canvas.drawLine(centerX, levelY, centerX, trackBottom, paint)
        paint.alpha = 255

        drawStreamIcon(
            canvas,
            stream,
            centerX,
            scaled(199.1f),
            activeColor,
            visualAlpha,
            streamState.level <= streamState.levelMin || streamState.muted,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            listener?.dismiss()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressedTopButton =
                    event.y <= scaled(32f) && event.x >= width - scaled(34f)
                pressedStream = if (pressedTopButton) INVALID_STREAM else streamAt(event.x)
                pressedIcon = event.y >= height - scaled(43f)
                dragging = false
                listener?.onInteraction()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedStream == INVALID_STREAM || pressedIcon) return true
                if (!dragging &&
                    (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                ) {
                    dragging = true
                }
                if (dragging) setLevelFromTouch(pressedStream, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                when {
                    pressedTopButton && !movedBeyondSlop(event) -> {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        listener?.toggleExpanded()
                    }
                    pressedStream != INVALID_STREAM && pressedIcon && !movedBeyondSlop(event) -> {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        val streamState = state?.states?.get(pressedStream)
                        val restore =
                            lastNonZero[pressedStream]
                                ?: streamState?.levelMax?.div(2)
                                ?: 1
                        listener?.toggleMute(pressedStream, restore)
                    }
                    pressedStream != INVALID_STREAM -> setLevelFromTouch(pressedStream, event.y)
                }
                resetTouch()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                resetTouch()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun setLevelFromTouch(stream: Int, y: Float) {
        if (isDisabled(stream)) return
        val streamState = state?.states?.get(stream) ?: return
        val top = scaled(46.6f)
        val bottom = scaled(186.1f)
        val fraction = ((bottom - y) / max(1f, bottom - top)).coerceIn(0f, 1f)
        val level =
            streamState.levelMin +
                ((streamState.levelMax - streamState.levelMin) * fraction).roundToInt()
        listener?.setVolume(stream, level)
    }

    private fun streamAt(x: Float): Int {
        val streams = visibleStreams()
        if (streams.isEmpty()) return INVALID_STREAM
        if (streams.size == 1) return streams[0]
        val alarmCenter = width - scaled(EXPANDED_STREAM_RIGHT_INSETS[0])
        val ringCenter = width - scaled(EXPANDED_STREAM_RIGHT_INSETS[1])
        val mediaCenter = width - scaled(EXPANDED_STREAM_RIGHT_INSETS[2])
        val index = when {
            x < (alarmCenter + ringCenter) / 2f -> 0
            x < (ringCenter + mediaCenter) / 2f -> 1
            else -> 2
        }
        return streams[index]
    }

    private fun visibleStreams(): IntArray {
        if (expanded || expansion > 0f) {
            return intArrayOf(
                AudioManager.STREAM_ALARM,
                AudioManager.STREAM_RING,
                AudioManager.STREAM_MUSIC,
            )
        }
        val candidate = state?.activeStream ?: AudioManager.STREAM_MUSIC
        return intArrayOf(
            if (state?.states?.get(candidate) != null) candidate else AudioManager.STREAM_MUSIC,
        )
    }

    private fun isDisabled(stream: Int): Boolean {
        val current = state ?: return false
        return when (stream) {
            AudioManager.STREAM_MUSIC -> current.disallowMedia
            AudioManager.STREAM_RING -> current.disallowRinger
            AudioManager.STREAM_ALARM -> current.disallowAlarms
            AudioManager.STREAM_SYSTEM -> current.disallowSystem
            else -> false
        }
    }

    private fun drawStreamIcon(
        canvas: Canvas,
        stream: Int,
        x: Float,
        y: Float,
        color: Int,
        alpha: Float,
        muted: Boolean,
    ) {
        paint.color = color
        paint.alpha = (255 * alpha).roundToInt()
        paint.strokeWidth = dp(1.8f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(REFERENCE_SCALE, REFERENCE_SCALE)
        when (stream) {
            AudioManager.STREAM_RING -> drawBell(canvas, 0f, 0f)
            AudioManager.STREAM_ALARM -> drawAlarm(canvas, 0f, 0f)
            else -> drawSpeaker(canvas, 0f, 0f, muted)
        }
        canvas.restore()
        paint.alpha = 255
    }

    private fun drawSpeaker(canvas: Canvas, x: Float, y: Float, muted: Boolean) {
        path.reset()
        path.moveTo(x - dp(5f), y - dp(2f))
        path.lineTo(x - dp(2f), y - dp(2f))
        path.lineTo(x + dp(1f), y - dp(5f))
        path.lineTo(x + dp(1f), y + dp(5f))
        path.lineTo(x - dp(2f), y + dp(2f))
        path.lineTo(x - dp(5f), y + dp(2f))
        path.close()
        canvas.drawPath(path, paint)
        if (muted) {
            canvas.drawLine(x + dp(3f), y - dp(3f), x + dp(7f), y + dp(3f), paint)
            canvas.drawLine(x + dp(7f), y - dp(3f), x + dp(3f), y + dp(3f), paint)
        } else {
            canvas.drawArc(
                x - dp(1f),
                y - dp(5f),
                x + dp(7f),
                y + dp(5f),
                -55f,
                110f,
                false,
                paint,
            )
        }
    }

    private fun drawBell(canvas: Canvas, x: Float, y: Float) {
        path.reset()
        path.moveTo(x - dp(5f), y + dp(2f))
        path.quadTo(x - dp(3f), y, x - dp(3f), y - dp(3f))
        path.quadTo(x, y - dp(7f), x + dp(3f), y - dp(3f))
        path.quadTo(x + dp(3f), y, x + dp(5f), y + dp(2f))
        path.lineTo(x - dp(5f), y + dp(2f))
        canvas.drawPath(path, paint)
        canvas.drawCircle(x, y + dp(4f), dp(1f), paint)
    }

    private fun drawAlarm(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, dp(5f), paint)
        canvas.drawLine(x, y, x, y - dp(3f), paint)
        canvas.drawLine(x, y, x + dp(2.5f), y + dp(1.5f), paint)
        canvas.drawLine(x - dp(6f), y - dp(5f), x - dp(3f), y - dp(7f), paint)
        canvas.drawLine(x + dp(6f), y - dp(5f), x + dp(3f), y - dp(7f), paint)
    }

    private fun movedBeyondSlop(event: MotionEvent): Boolean =
        abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop

    private fun resetTouch() {
        pressedStream = INVALID_STREAM
        pressedTopButton = false
        pressedIcon = false
        dragging = false
    }

    private fun updateCardBounds() {
        val cardWidth = scaled(lerp(36f, 90f, expansion))
        cardBounds.set(
            width - cardWidth,
            scaled(8f),
            width.toFloat(),
            height.toFloat(),
        )
    }

    private fun systemColor(resource: Int, fallback: Int): Int =
        try {
            context.getColor(resource)
        } catch (_: Exception) {
            fallback
        }

    private fun dp(value: Float): Float = value * density

    /** Converts a measurement from the source reference into device pixels. */
    private fun scaled(value: Float): Float = dp(value * REFERENCE_SCALE)

    private fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * amount

    companion object {
        private const val INVALID_STREAM = -1000
        private const val REFERENCE_SCALE = 1.3f
        private const val FIXED_RIGHT_INSET = 18f
        private const val MEDIA_STREAM_INDEX = 2
        private val EXPANDED_STREAM_RIGHT_INSETS = floatArrayOf(74f, 47f, FIXED_RIGHT_INSET)
    }
}
