/*
 * Copyright (C) 2026 The uwuAOSP Project
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

package org.uwuaosp.systemui.moment.arc

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.WindowConfiguration
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import org.uwuaosp.systemui.moment.arc.R
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

class MomentArcController(
    private val sysuiContext: Context,
    private val pluginContext: Context,
) {
    private val launcherApps = sysuiContext.getSystemService(LauncherApps::class.java)
    private val windowManager = sysuiContext.getSystemService(WindowManager::class.java)
    private val touchCoordinatesQueue = ConcurrentLinkedQueue<Triple<Float, Float, Boolean>>()

    private var overlayView: MomentArcView? = null
    private var isGestureActive = false

    fun show(isLeft: Boolean, initialTouchX: Float = -1f, initialTouchY: Float = -1f) {
        hide()
        if (!isMomentEnabled()) return

        val innerTargets = getInnerRingTargets()
        val outerTargets = getOuterRingTargets()
        val momentArcView = MomentArcView(sysuiContext, isLeft)
        val displayedInnerTargets = ArrayList<MomentArcTarget>()
        val displayedOuterTargets = ArrayList<MomentArcTarget>()

        innerTargets.take(INNER_MAX_ICONS - 1).forEach { target ->
            createIconView(target)?.let { iconView ->
                momentArcView.addView(iconView)
                displayedInnerTargets.add(target)
            }
        }

        while (momentArcView.childCount < INNER_MAX_ICONS - 1) {
            momentArcView.addView(View(sysuiContext).apply { visibility = View.INVISIBLE })
        }

        // Keep the all-apps affordance available even when no quick apps are configured.
        momentArcView.addView(
            ImageView(sysuiContext).apply {
                setImageDrawable(pluginContext.getDrawable(R.drawable.ic_moment_arc_all_apps))
            }
        )

        outerTargets.take(OUTER_MAX_ICONS).forEach { target ->
            createIconView(target)?.let { iconView ->
                momentArcView.addView(iconView)
                displayedOuterTargets.add(target)
            }
        }

        momentArcView.setOnIconLaunchListener { index ->
            if (!isMomentEnabled()) {
                hide()
                return@setOnIconLaunchListener
            }
            when {
                index < INNER_MAX_ICONS - 1 ->
                    displayedInnerTargets.getOrNull(index)?.let(::launchTarget)
                index == INNER_MAX_ICONS - 1 -> launchAllApps()
                else -> displayedOuterTargets
                    .getOrNull(index - INNER_MAX_ICONS)
                    ?.let(::launchTarget)
            }
            hide()
        }
        momentArcView.setOnDismissListener { hide() }
        if (initialTouchX >= 0f && initialTouchY >= 0f) {
            momentArcView.setInitialTouchPoint(initialTouchX, initialTouchY)
        }

        try {
            windowManager.addView(momentArcView, MomentArcView.createLayoutParams())
            overlayView = momentArcView
            while (touchCoordinatesQueue.isNotEmpty()) {
                touchCoordinatesQueue.poll()?.let { (x, y, isUp) ->
                    momentArcView.dispatchTouchCoordinates(x, y, isUp)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add MomentArc view", e)
            overlayView = null
            isGestureActive = false
            touchCoordinatesQueue.clear()
        }
    }

    fun onTouchCoordinates(x: Float, y: Float, isUp: Boolean) {
        if (!isMomentEnabled()) {
            hide()
            return
        }
        if (!isGestureActive && !isUp) {
            isGestureActive = true
        }

        overlayView?.dispatchTouchCoordinates(x, y, isUp)
            ?: touchCoordinatesQueue.add(Triple(x, y, isUp))

        if (isUp) {
            isGestureActive = false
            touchCoordinatesQueue.clear()
        }
    }

    fun hide() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove MomentArc view", e)
            }
        }
        overlayView = null
        isGestureActive = false
        touchCoordinatesQueue.clear()
    }

    fun isMomentEnabled(): Boolean {
        return Settings.Secure.getIntForUser(
            sysuiContext.contentResolver,
            Settings.Secure.MOMENT_ENABLED,
            0,
            ActivityManager.getCurrentUser(),
        ) != 0
    }

    private fun createIconView(target: MomentArcTarget): ImageView? {
        val icon = runCatching { loadIcon(target) }
            .onFailure { Log.w(TAG, "Failed to load icon for $target", it) }
            .getOrNull()
            ?: return null
        return ImageView(sysuiContext).apply {
            setImageDrawable(icon)
            iconToCircle()
        }
    }

    private fun loadIcon(target: MomentArcTarget): Drawable {
        return when (target) {
            is MomentArcTarget.App -> fallbackAppIcon(target.packageName)
            is MomentArcTarget.Shortcut -> {
                val shortcutInfo = getShortcutInfo(target)
                    ?: return fallbackAppIcon(target.packageName)
                launcherApps.getShortcutBadgedIconDrawable(
                    shortcutInfo,
                    sysuiContext.resources.displayMetrics.densityDpi,
                ) ?: fallbackAppIcon(target.packageName)
            }
        }
    }

    private fun fallbackAppIcon(packageName: String): Drawable =
        getCurrentUserContext().packageManager.getApplicationInfo(packageName, 0)
            .loadIcon(getCurrentUserContext().packageManager)

    private fun View.iconToCircle() {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val size = min(view.width, view.height)
                if (size <= 0) {
                    outline.setEmpty()
                    return
                }
                val left = (view.width - size) / 2
                val top = (view.height - size) / 2
                outline.setRoundRect(left, top, left + size, top + size, size / 2f)
            }
        }
        clipToOutline = true
    }

    private fun launchTarget(target: MomentArcTarget) {
        when (target) {
            is MomentArcTarget.App -> launchPackage(target.packageName)
            is MomentArcTarget.Shortcut -> launchShortcut(target)
        }
    }

    private fun launchPackage(packageName: String) {
        val userContext = getCurrentUserContext()
        val launchIntent = userContext.packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = buildMomentOptions()
        runCatching {
            sysuiContext.startActivityAsUser(launchIntent, options.toBundle(), getCurrentUserHandle())
        }.onFailure {
            Log.w(TAG, "Failed to launch package $packageName", it)
        }
    }

    private fun launchShortcut(target: MomentArcTarget.Shortcut) {
        val options = buildMomentOptions().apply {
            setApplyMultipleTaskFlagForShortcut(true)
        }
        runCatching {
            launcherApps.startShortcut(
                target.packageName,
                target.shortcutId,
                null,
                options.toBundle(),
                UserHandle.of(target.userId),
            )
        }.onFailure {
            Log.w(TAG, "Failed to launch shortcut ${target.packageName}/${target.shortcutId}", it)
        }
    }

    private fun launchAllApps() {
        val intent =
            Intent(Intent.ACTION_ALL_APPS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        runCatching {
            sysuiContext.startActivityAsUser(intent, getCurrentUserHandle())
        }.onFailure {
            Log.w(TAG, "Failed to open launcher all-apps", it)
        }
    }

    private fun buildMomentOptions(): ActivityOptions {
        return ActivityOptions.makeBasic().apply {
            setLaunchWindowingMode(WindowConfiguration.WINDOWING_MODE_MOMENT)
            setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        }
    }

    private fun getInnerRingTargets(): List<MomentArcTarget> {
        return readTargets(INNER_RING_TARGETS)
    }

    private fun getOuterRingTargets(): List<MomentArcTarget> {
        return readTargets(OUTER_RING_TARGETS)
    }

    private fun readTargets(key: String): List<MomentArcTarget> {
        val selected =
            Settings.System.getStringForUser(
                sysuiContext.contentResolver,
                key,
                getCurrentUserId(),
            ).orEmpty()
        return selected.split(ENTRY_SEPARATOR).mapNotNull(::parseTarget)
    }

    private fun parseTarget(rawEntry: String): MomentArcTarget? {
        val entry = rawEntry.trim()
        if (entry.isBlank()) {
            return null
        }
        if (!entry.startsWith(ENTRY_PREFIX_APP) && !entry.startsWith(ENTRY_PREFIX_SHORTCUT)) {
            return MomentArcTarget.App(entry)
        }

        return when {
            entry.startsWith(ENTRY_PREFIX_APP) -> {
                val packageName = Uri.decode(entry.removePrefix(ENTRY_PREFIX_APP)).trim()
                packageName.takeIf { it.isNotBlank() }?.let(MomentArcTarget::App)
            }
            entry.startsWith(ENTRY_PREFIX_SHORTCUT) -> parseShortcutTarget(entry)
            else -> null
        }
    }

    private fun parseShortcutTarget(entry: String): MomentArcTarget.Shortcut? {
        val parts = entry.split(ENTRY_FIELD_SEPARATOR, limit = 4)
        if (parts.size !in 3..4) {
            Log.w(TAG, "Ignoring malformed MomentArc shortcut entry: $entry")
            return null
        }

        val hasExplicitUserId = parts.size == 4
        val userId =
            if (hasExplicitUserId) {
                parts[1].toIntOrNull()
            } else {
                getCurrentUserId()
            }
        val packageName = Uri.decode(parts[if (hasExplicitUserId) 2 else 1]).trim()
        val shortcutId = Uri.decode(parts[if (hasExplicitUserId) 3 else 2]).trim()
        if (userId == null || packageName.isBlank() || shortcutId.isBlank()) {
            Log.w(TAG, "Ignoring malformed MomentArc shortcut entry: $entry")
            return null
        }
        return MomentArcTarget.Shortcut(
            packageName = packageName,
            shortcutId = shortcutId,
            userId = userId,
        )
    }

    private fun getShortcutInfo(target: MomentArcTarget.Shortcut): ShortcutInfo? {
        return queryShortcutInfo(
            target,
            LauncherApps.ShortcutQuery.FLAG_MATCH_ALL_KINDS_WITH_ALL_PINNED,
        ) ?: queryShortcutInfo(
            target,
            LauncherApps.ShortcutQuery.FLAG_MATCH_ALL_KINDS,
        )
    }

    private fun queryShortcutInfo(
        target: MomentArcTarget.Shortcut,
        queryFlags: Int,
    ): ShortcutInfo? {
        return runCatching {
            launcherApps.getShortcuts(
                LauncherApps.ShortcutQuery()
                    .setPackage(target.packageName)
                    .setShortcutIds(listOf(target.shortcutId))
                    .setQueryFlags(queryFlags),
                UserHandle.of(target.userId),
            )
        }.onFailure {
            Log.w(
                TAG,
                "Failed to query shortcut ${target.packageName}/${target.shortcutId} with flags=$queryFlags",
                it,
            )
        }.getOrNull()?.firstOrNull()
    }

    private fun getCurrentUserContext(): Context {
        return sysuiContext.createContextAsUser(getCurrentUserHandle(), 0)
    }

    private fun getCurrentUserHandle(): UserHandle = UserHandle.of(getCurrentUserId())

    private fun getCurrentUserId(): Int = ActivityManager.getCurrentUser()

    companion object {
        private const val TAG = "MomentArc"
        private const val INNER_MAX_ICONS = 6
        private const val OUTER_MAX_ICONS = 7
        private const val ENTRY_SEPARATOR = "|"
        private const val ENTRY_PREFIX_APP = "app:"
        private const val ENTRY_PREFIX_SHORTCUT = "shortcut:"
        private const val ENTRY_FIELD_SEPARATOR = ":"
        private const val INNER_RING_TARGETS = "moment_arc_selected_targets"
        private const val OUTER_RING_TARGETS =
            "moment_arc_outer_ring_selected_targets"
    }

    private sealed interface MomentArcTarget {
        data class App(val packageName: String) : MomentArcTarget

        data class Shortcut(
            val packageName: String,
            val shortcutId: String,
            val userId: Int,
        ) : MomentArcTarget
    }
}
