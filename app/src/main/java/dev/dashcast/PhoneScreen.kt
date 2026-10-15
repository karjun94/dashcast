package dev.dashcast

import android.content.Context

/**
 * Phone screen options while casting: an orientation lock (so the stream doesn't change shape when the
 * phone is moved) and lowest brightness (less heat and battery; the stream itself stays bright).
 * Applied by [TouchService] with an invisible overlay and released when casting stops.
 */
object PhoneScreen {
    val MODES = listOf("off", "landscape", "portrait")
    private const val PREFS = "dashcast"

    /** Orientation lock: off | landscape | portrait. */
    @Volatile var mode = "off"
        private set
    @Volatile var dim = false
        private set

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = p.getString("orientation", "off") ?: "off"
        dim = p.getBoolean("dim", false)
    }

    fun set(ctx: Context, m: String) {
        if (m !in MODES) return
        mode = m
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("orientation", m).apply()
        apply()
    }

    fun setDim(ctx: Context, on: Boolean) {
        dim = on
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("dim", on).apply()
        apply()
    }

    /** Active only while casting; otherwise the phone behaves normally. */
    fun apply() {
        val casting = CaptureService.running
        TouchService.instance?.applyScreen(if (casting) mode else "off", casting && dim)
    }
}
