package dev.dashcast

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Turns normalised touch points from the browser into real gestures on the phone.
 * Needs to be enabled once in Settings -> Accessibility -> DashCast touch control.
 * No root and no ADB are required.
 */
class TouchService : AccessibilityService() {

    companion object {
        @Volatile var instance: TouchService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var lockedMode = "off"
    private var lockedOrientation: Int? = null
    private var dimmed = false

    override fun onServiceConnected() {
        instance = this
        PhoneScreen.load(this)
        PhoneScreen.apply()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        applyScreen("off", false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        applyScreen("off", false)
        super.onDestroy()
    }

    /**
     * Orientation lock and lowest brightness, both done with one invisible, untouchable 1x1 overlay window
     * (removed when neither is wanted). Accessibility overlays need no "display over other apps" permission.
     * Brightness only changes the backlight, so the captured picture is unaffected.
     */
    fun applyScreen(mode: String, dim: Boolean) {
        main.post {
            val wm = getSystemService(WindowManager::class.java)
            if (mode != lockedMode) {
                // Fixed (not sensor) orientations, keeping whichever side the phone is on now,
                // so turning the phone around doesn't flip the picture.
                @Suppress("DEPRECATION")
                val rotation = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation
                lockedOrientation = when (mode) {
                    "landscape" -> if (rotation == Surface.ROTATION_270) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                                   else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    "portrait" -> if (rotation == Surface.ROTATION_180) ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                                  else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else -> null
                }
                lockedMode = mode
            }
            dimmed = dim
            val v = overlay
            if (lockedOrientation == null && !dimmed) {
                if (v != null) try { wm.removeView(v) } catch (_: Exception) {}
                overlay = null
                return@post
            }
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                screenOrientation = lockedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                screenBrightness = if (dimmed) 0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            try {
                if (v == null) overlay = View(this).also { wm.addView(it, lp) } else wm.updateViewLayout(v, lp)
            } catch (e: Exception) {
                android.util.Log.w("TouchService", "screen overlay failed: ${e.message}")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun screen(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        return dm
    }

    /**
     * points: normalised [x, y] in 0..1, first = finger down, last = finger up.
     * One point = tap (short duration) or long-press (long duration).
     */
    fun gesture(points: List<FloatArray>, durationMs: Long) {
        if (points.isEmpty() || !CaptureService.running) return
        val dm = screen()
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = (p[0] * w).coerceIn(0f, w - 1f)
            val y = (p[1] * h).coerceIn(0f, h - 1f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val dur = durationMs.coerceIn(1L, GestureDescription.getMaxGestureDuration())
        val stroke = GestureDescription.StrokeDescription(path, 0, dur)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun globalKey(action: String) {
        if (!CaptureService.running) return
        when (action) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }
}
