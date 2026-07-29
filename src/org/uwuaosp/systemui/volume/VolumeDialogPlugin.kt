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

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import com.android.systemui.plugins.PluginDependency
import com.android.systemui.plugins.VolumeDialog
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.plugins.VolumeDialogSafetyController
import com.android.systemui.plugins.annotations.Requires
import kotlin.math.roundToInt

@Requires(target = VolumeDialog::class, version = VolumeDialog.VERSION)
@Requires(target = VolumeDialog.Callback::class, version = VolumeDialog.Callback.VERSION)
@Requires(target = VolumeDialogController::class, version = VolumeDialogController.VERSION)
@Requires(
    target = VolumeDialogSafetyController::class,
    version = VolumeDialogSafetyController.VERSION,
)
class VolumeDialogPlugin : VolumeDialog, VolumeDialogController.Callbacks {
    private val handler = Handler(Looper.getMainLooper())
    private val interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    private lateinit var sysuiContext: Context
    private lateinit var pluginContext: Context
    private lateinit var controller: VolumeDialogController
    private lateinit var safetyController: VolumeDialogSafetyController
    private lateinit var windowManager: WindowManager
    private lateinit var panel: VolumePanelView
    private lateinit var windowParams: WindowManager.LayoutParams

    private var callback: VolumeDialog.Callback? = null
    private var state: VolumeDialogController.State? = null
    private var showing = false
    private var expanded = false
    private var accessibilityMode = false
    private var sizeAnimator: ValueAnimator? = null

    private val timeout = Runnable { dismiss() }

    override fun onCreate(sysuiContext: Context, pluginContext: Context) {
        this.sysuiContext = sysuiContext
        this.pluginContext = pluginContext
        controller = PluginDependency.get(this, VolumeDialogController::class.java)
        safetyController = PluginDependency.get(this, VolumeDialogSafetyController::class.java)
        windowManager = sysuiContext.getSystemService(WindowManager::class.java)
    }

    override fun init(windowType: Int, callback: VolumeDialog.Callback) {
        this.callback = callback
        panel = createPanel()
        windowParams = createWindowParams(windowType)
        controller.addCallback(this, handler)
        controller.getState()
    }

    override fun destroy() {
        handler.removeCallbacks(timeout)
        sizeAnimator?.cancel()
        controller.removeCallback(this)
        if (showing) {
            windowManager.removeViewImmediate(panel)
            controller.notifyVisible(false)
            showing = false
        }
        safetyController.dismissWarnings()
        callback = null
    }

    private fun createPanel(): VolumePanelView =
        VolumePanelView(pluginContext).apply {
            listener =
                object : VolumePanelView.Listener {
                    override fun setVolume(stream: Int, level: Int) {
                        controller.setActiveStream(stream, true)
                        controller.setStreamVolume(stream, level, true)
                        rescheduleTimeout()
                    }

                    override fun toggleMute(stream: Int, restoreLevel: Int) {
                        val streamState = state?.states?.get(stream) ?: return
                        val target = if (streamState.level > streamState.levelMin) {
                            streamState.levelMin
                        } else {
                            restoreLevel.coerceIn(streamState.levelMin, streamState.levelMax)
                        }
                        controller.setActiveStream(stream, true)
                        controller.setStreamVolume(stream, target, true)
                        rescheduleTimeout()
                    }

                    override fun toggleExpanded() {
                        setExpanded(!expanded)
                    }

                    override fun onInteraction() {
                        controller.userActivity()
                        rescheduleTimeout()
                    }

                    override fun dismiss() {
                        this@VolumeDialogPlugin.dismiss()
                    }
                }
            updateState(state, expanded)
        }

    private fun createWindowParams(type: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dp(EXPANDED_WIDTH_DP),
            dp(EXPANDED_HEIGHT_DP),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(EDGE_MARGIN_DP)
            y = -dp(VERTICAL_OFFSET_DP)
            title = "uwu volume dialog"
            windowAnimations = 0
            privateFlags = privateFlags or
                WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
        }

    private fun show() {
        if (!showing) {
            expanded = false
            panel.updateState(state, expanded)
            panel.expansion = 0f
            windowManager.addView(panel, windowParams)
            panel.alpha = 0f
            panel.translationX = dp(24).toFloat()
            panel.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(SHOW_DURATION_MS)
                .setInterpolator(interpolator)
                .start()
            showing = true
            controller.notifyVisible(true)
        }
        rescheduleTimeout()
    }

    private fun dismiss() {
        if (!showing) return
        handler.removeCallbacks(timeout)
        sizeAnimator?.cancel()
        showing = false
        panel.animate()
            .alpha(0f)
            .translationX(dp(24).toFloat())
            .setDuration(HIDE_DURATION_MS)
            .setInterpolator(interpolator)
            .withEndAction {
                if (panel.isAttachedToWindow) {
                    windowManager.removeViewImmediate(panel)
                }
            }
            .start()
        controller.notifyVisible(false)
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value || !showing) return
        expanded = value
        panel.updateState(state, expanded)
        sizeAnimator?.cancel()
        val start = panel.expansion
        val end = if (expanded) 1f else 0f
        sizeAnimator =
            ValueAnimator.ofFloat(start, end).apply {
                duration = EXPAND_DURATION_MS
                interpolator = this@VolumeDialogPlugin.interpolator
                addUpdateListener {
                    val progress = it.animatedValue as Float
                    panel.expansion = progress
                }
                start()
            }
        rescheduleTimeout()
    }

    private fun recreateForConfiguration() {
        if (!::panel.isInitialized) return
        val wasShowing = showing
        val wasExpanded = expanded
        if (wasShowing) {
            windowManager.removeViewImmediate(panel)
        }
        panel = createPanel()
        expanded = wasExpanded
        panel.expansion = if (expanded) 1f else 0f
        windowParams.width = dp(EXPANDED_WIDTH_DP)
        windowParams.height = dp(EXPANDED_HEIGHT_DP)
        if (wasShowing) {
            windowManager.addView(panel, windowParams)
        }
    }

    private fun rescheduleTimeout() {
        handler.removeCallbacks(timeout)
        if (showing) {
            handler.postDelayed(
                timeout,
                if (expanded || accessibilityMode) EXPANDED_TIMEOUT_MS else TIMEOUT_MS,
            )
        }
    }

    override fun onShowRequested(reason: Int, keyguardLocked: Boolean, lockTaskModeState: Int) {
        show()
    }

    override fun onDismissRequested(reason: Int) = dismiss()

    override fun onStateChanged(state: VolumeDialogController.State?) {
        this.state = state?.copy()
        if (::panel.isInitialized) {
            panel.updateState(this.state, expanded)
        }
    }

    override fun onLayoutDirectionChanged(layoutDirection: Int) {
        if (::panel.isInitialized) panel.layoutDirection = layoutDirection
    }

    override fun onConfigurationChanged() = recreateForConfiguration()

    override fun onShowVibrateHint() = rescheduleTimeout()

    override fun onShowSilentHint() = rescheduleTimeout()

    override fun onScreenOff() = dismiss()

    override fun onShowSafetyWarning(flags: Int) {
        safetyController.showSafetyWarning(flags)
        rescheduleTimeout()
    }

    override fun onAccessibilityModeChanged(showA11yStream: Boolean?) {
        accessibilityMode = showA11yStream == true
        rescheduleTimeout()
    }

    override fun onCaptionComponentStateChanged(
        isComponentEnabled: Boolean?,
        fromTooltip: Boolean?,
    ) = Unit

    override fun onCaptionEnabledStateChanged(
        isEnabled: Boolean?,
        checkBeforeSwitch: Boolean?,
    ) = Unit

    override fun onShowCsdWarning(csdWarning: Int, durationMs: Int) {
        safetyController.showCsdWarning(csdWarning, durationMs)
        rescheduleTimeout()
    }

    override fun onVolumeChangedFromKey() = rescheduleTimeout()

    private fun dp(value: Int): Int =
        (value * pluginContext.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val EXPANDED_WIDTH_DP = 150
        private const val EXPANDED_HEIGHT_DP = 280
        private const val EDGE_MARGIN_DP = 10
        private const val VERTICAL_OFFSET_DP = 80
        private const val SHOW_DURATION_MS = 160L
        private const val HIDE_DURATION_MS = 120L
        private const val EXPAND_DURATION_MS = 220L
        private const val TIMEOUT_MS = 3000L
        private const val EXPANDED_TIMEOUT_MS = 5000L
    }
}
