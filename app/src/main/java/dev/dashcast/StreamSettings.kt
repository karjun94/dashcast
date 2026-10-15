package dev.dashcast

import android.content.Context

/**
 * Saved stream tuning, chosen in the app. Applied live while casting; a viewer URL (?fps=&edge=&q=)
 * can still override it until casting restarts.
 */
object StreamSettings {
    val FPS_OPTIONS = listOf(15, 30, 45, 60)
    val EDGE_OPTIONS = listOf(640, 800, 960, 1280)          // longest side of the streamed image, px
    val QUALITY_OPTIONS = listOf(40 to "Low", 55 to "Medium", 70 to "High")
    private const val PREFS = "dashcast"

    @Volatile var fps = 30
        private set
    @Volatile var edge = 960
        private set
    @Volatile var quality = 55
        private set

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fps = p.getInt("fps", fps)
        edge = p.getInt("edge", edge)
        quality = p.getInt("quality", quality)
    }

    fun set(ctx: Context, fps: Int = this.fps, edge: Int = this.edge, quality: Int = this.quality) {
        this.fps = fps; this.edge = edge; this.quality = quality
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("fps", fps).putInt("edge", edge).putInt("quality", quality).apply()
        CaptureService.current?.tune(quality, fps, edge)
    }
}
