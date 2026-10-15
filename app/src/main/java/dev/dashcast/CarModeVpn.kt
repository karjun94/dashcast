package dev.dashcast

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream

/**
 * Car mode: gives the phone a fixed, public-looking address ([ADDRESS]) so car browsers that refuse private
 * addresses (192.168.x.x etc.) can still open DashCast at http://6.6.6.6:8080.
 *
 * The VPN only claims that one address and routes nothing else, so other traffic is untouched and nothing goes
 * over the internet. A car on the phone's hotspot sends packets for 6.6.6.6 to the phone, which delivers them
 * locally because the address belongs to this VPN's interface. DashCast itself is excluded from the VPN so its
 * replies go straight back out over the hotspot.
 */
class CarModeVpn : VpnService() {

    companion object {
        const val ADDRESS = "6.6.6.6"
        private const val ACTION_STOP = "dev.dashcast.CAR_MODE_STOP"
        private const val PREFS = "dashcast"

        /** off | on | needs permission | error */
        @Volatile var state = "off"
            private set

        fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("car_mode", false)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("car_mode", on).apply()
            if (!CaptureService.running) return
            if (on) start(ctx) else stop(ctx)
        }

        fun start(ctx: Context) {
            if (prepare(ctx) != null) { state = "needs permission"; return }   // user hasn't approved the VPN
            ctx.startService(Intent(ctx, CarModeVpn::class.java))
        }

        fun stop(ctx: Context) {
            if (state == "off") return
            ctx.startService(Intent(ctx, CarModeVpn::class.java).setAction(ACTION_STOP))
        }
    }

    private val tag = "CarModeVpn"
    private var tun: ParcelFileDescriptor? = null
    private var drain: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { close(); stopSelf(); return START_NOT_STICKY }
        if (tun != null) return START_NOT_STICKY
        try {
            val pfd = Builder()
                .setSession("DashCast car mode")
                .addAddress(ADDRESS, 32)
                .addRoute(ADDRESS, 32)                 // nothing else is routed through the VPN
                .addDisallowedApplication(packageName) // DashCast's replies use the normal hotspot route
                .setBlocking(true)
                .establish()
            if (pfd == null) { state = "needs permission"; stopSelf(); return START_NOT_STICKY }
            tun = pfd
            // Nothing should be sent into the VPN, but read and discard anything that is so it can't back up.
            drain = Thread({
                val buf = ByteArray(32 * 1024)
                try { FileInputStream(pfd.fileDescriptor).use { while (it.read(buf) >= 0) {} } } catch (_: Exception) {}
            }, "car-mode-drain").also { it.start() }
            state = "on"
            Log.i(tag, "car mode on: $ADDRESS")
        } catch (e: Exception) {
            state = "error"
            Log.e(tag, "car mode failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {   // the user turned the VPN off in system settings, or another VPN took over
        close()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        close()
        super.onDestroy()
    }

    private fun close() {
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        drain?.interrupt()
        drain = null
        state = "off"
    }
}
