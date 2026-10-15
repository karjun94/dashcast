package dev.dashcast

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private companion object {
        const val REQ_CAPTURE = 42
        const val REQ_PERMS = 1
        const val REQ_AUDIO = 2
        const val REQ_VPN = 3
        val BG = Color.parseColor("#0F1115")
        val CARD = Color.parseColor("#1A1D23")
        val TEXT = Color.parseColor("#ECEFF4")
        val MUTED = Color.parseColor("#9AA3B2")
        val ACCENT = Color.parseColor("#4F8CFF")
        val OK = Color.parseColor("#34C759")
        val WARN = Color.parseColor("#FFB020")
        val STOP = Color.parseColor("#3A2326")
        val STOP_TEXT = Color.parseColor("#FF8A80")
        /** Names Android commonly gives the hotspot interface. */
        val HOTSPOT = Regex("^(ap\\d|swlan\\d|wlan1|softap\\d|wigig\\d)")
        /** Mobile data, VPN and virtual interfaces: the car can't reach these. */
        val MOBILE_DATA = Regex("^(rmnet|ccmni|pdp|v4-|tun|dummy|p2p)")
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var stateDot: View
    private lateinit var stateTitle: TextView
    private lateinit var stateSub: TextView
    private lateinit var primary: TextView
    private lateinit var urlBox: LinearLayout
    private lateinit var touchRow: Row
    private lateinit var soundRow: Row
    private lateinit var batteryRow: Row
    private val rotChips = LinkedHashMap<String, TextView>()
    private val dimChips = LinkedHashMap<Boolean, TextView>()
    private val fpsChips = LinkedHashMap<Int, TextView>()
    private val edgeChips = LinkedHashMap<Int, TextView>()
    private val qualityChips = LinkedHashMap<Int, TextView>()
    private lateinit var tuneNote: TextView
    private lateinit var rotNote: TextView
    private var shownUrls = ""
    private lateinit var portNote: TextView
    private val carChips = LinkedHashMap<Boolean, TextView>()
    private lateinit var carNote: TextView

    private val ticker = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        root.addView(text(28f, TEXT, bold = true).apply { text = "DashCast" })
        root.addView(text(15f, MUTED).apply {
            text = "Mirror this phone to any browser"
            setPadding(0, dp(2), 0, dp(20))
        })

        // Status + start/stop
        val status = card()
        val stateLine = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        stateDot = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(10) } }
        stateTitle = text(20f, TEXT, bold = true)
        stateLine.addView(stateDot); stateLine.addView(stateTitle)
        stateSub = text(14f, MUTED).apply { setPadding(0, dp(4), 0, dp(16)) }
        primary = text(17f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(15), 0, dp(15))
            setOnClickListener { if (CaptureService.running) stopCasting() else startCasting() }
        }
        status.addView(stateLine); status.addView(stateSub); status.addView(primary)
        root.addView(status)

        // Phone screen: orientation lock + brightness
        PhoneScreen.load(this)
        val rot = card("PHONE SCREEN WHILE CASTING")
        rot.addView(label("Orientation"))
        rot.addView(segmented(listOf("off" to "Auto", "landscape" to "Landscape", "portrait" to "Portrait"), rotChips) {
            PhoneScreen.set(this, it)
        })
        rot.addView(label("Brightness"))
        rot.addView(segmented(listOf(false to "Normal", true to "Lowest"), dimChips) {
            PhoneScreen.setDim(this, it)
        })
        rotNote = text(13f, MUTED).apply { setPadding(0, dp(10), 0, 0) }
        rot.addView(rotNote)
        root.addView(rot)

        // Stream tuning
        StreamSettings.load(this)
        val tune = card("STREAM QUALITY")
        tune.addView(label("Frame rate"))
        tune.addView(segmented(StreamSettings.FPS_OPTIONS.map { it to "$it fps" }, fpsChips) {
            StreamSettings.set(this, fps = it)
        })
        tune.addView(label("Resolution (longest side, px)"))
        tune.addView(segmented(StreamSettings.EDGE_OPTIONS.map { it to "$it" }, edgeChips) {
            StreamSettings.set(this, edge = it)
        })
        tune.addView(label("Image quality"))
        tune.addView(segmented(StreamSettings.QUALITY_OPTIONS, qualityChips) {
            StreamSettings.set(this, quality = it)
        })
        tuneNote = text(13f, MUTED).apply { setPadding(0, dp(10), 0, 0) }
        tune.addView(tuneNote)
        root.addView(tune)

        // Addresses
        val urls = card("OPEN IN ANY BROWSER ON THE SAME WI-FI")
        urls.addView(label("Car mode"))
        urls.addView(segmented(listOf(false to "Off", true to "On"), carChips) { on ->
            CarModeVpn.setEnabled(this, on)
            // Turning it on while casting needs the VPN approved first.
            if (on && CaptureService.running) VpnService.prepare(this)?.let {
                @Suppress("DEPRECATION")
                startActivityForResult(it, REQ_VPN)
            }
        })
        carNote = text(13f, MUTED).apply { setPadding(0, dp(8), 0, dp(4)) }
        urls.addView(carNote)
        urlBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        urls.addView(urlBox)
        portNote = text(13f, MUTED).apply { setPadding(0, dp(10), 0, 0) }
        urls.addView(portNote)
        root.addView(urls)

        // Setup checklist
        val setup = card("SETUP")
        touchRow = Row(setup, "Touch control")
        soundRow = Row(setup, "Sound in the browser")
        batteryRow = Row(setup, "Battery unrestricted")
        root.addView(setup)

        root.addView(text(13f, MUTED).apply {
            text = "When Android asks what to share, choose \"Entire screen\", then switch to the app you want to show.\n\n" +
                "For passengers or while parked. Don't interact with it while driving."
            setPadding(dp(4), dp(8), dp(4), 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(root)
        })

        // RECORD_AUDIO lets DashCast capture app sound (not the microphone) for the browser.
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), REQ_PERMS)
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    private fun startCasting() {
        // Car mode needs Android's one-time VPN approval before casting starts.
        if (CarModeVpn.enabled(this)) VpnService.prepare(this)?.let {
            @Suppress("DEPRECATION")
            startActivityForResult(it, REQ_VPN)
            return
        }
        requestCapture()
    }

    private fun requestCapture() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun stopCasting() {
        stopService(Intent(this, CaptureService::class.java))
        ui.postDelayed({ refresh() }, 300)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode != RESULT_OK) {
                CarModeVpn.setEnabled(this, false)
                Toast.makeText(this, "Car mode needs the VPN approval. It's off for now.", Toast.LENGTH_LONG).show()
            }
            if (CaptureService.running) { if (resultCode == RESULT_OK) CarModeVpn.start(this) } else requestCapture()
            refresh()
            return
        }
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
            startForegroundService(i)
            ui.postDelayed({ refresh() }, 800)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Asked from the Sound row and Android won't show the prompt any more: open the app's settings.
        if (requestCode == REQ_AUDIO && !hasAudio() &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        refresh()
    }

    private fun hasAudio() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Private IPv4 addresses, hotspot first: (interface, address, isHotspot). */
    private fun addresses(): List<Triple<String, String, Boolean>> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !MOBILE_DATA.containsMatchIn(it.name) }
            .flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { Triple(nif.name, it.hostAddress ?: "", HOTSPOT.containsMatchIn(nif.name)) }
            }
            .sortedByDescending { it.third }
    } catch (e: Exception) { emptyList() }

    private fun refresh() {
        val running = CaptureService.running
        val viewers = CaptureService.viewers
        stateDot.background = oval(if (running) (if (viewers > 0) OK else ACCENT) else MUTED)
        stateTitle.text = if (running) "Casting" else "Not casting"
        stateSub.text = when {
            !running -> "Tap Start, then choose \"Entire screen\"."
            viewers == 0 -> "Waiting for a browser to connect."
            viewers == 1 -> "1 viewer connected."
            else -> "$viewers viewers connected."
        }
        primary.text = if (running) "Stop casting" else "Start casting"
        primary.setTextColor(if (running) STOP_TEXT else Color.WHITE)
        primary.background = pressable(if (running) STOP else ACCENT, 14)

        val addrs = addresses()
        val port = CaptureService.port
        val carMode = CarModeVpn.enabled(this)
        val key = addrs.joinToString { "${it.first}${it.second}" } + ":$port:$carMode"
        if (key != shownUrls) {
            shownUrls = key
            urlBox.removeAllViews()
            if (carMode) urlBox.addView(carRow(port))
            if (addrs.isEmpty() && !carMode) {
                urlBox.addView(text(15f, WARN).apply { text = "No network. Turn on the Wi-Fi hotspot." })
            }
            addrs.forEachIndexed { i, (name, ip, hotspot) -> urlBox.addView(urlRow(name, ip, port, !carMode && (hotspot || i == 0))) }
        }
        portNote.text = when {
            running && CaptureService.portFallback ->
                "This phone doesn't let apps use port 80, so the address needs :$port at the end."
            running -> "Using port 80, so no :port is needed."
            else -> "The name form (sslip.io) is for browsers that won't open a plain IP address. It needs internet to look up."
        }
        portNote.setTextColor(if (running && CaptureService.portFallback) WARN else MUTED)

        select(carChips, carMode)
        val carState = CarModeVpn.state
        carNote.text = when {
            !carMode -> "For car browsers that refuse addresses like 192.168.x.x. Uses a local-only VPN while casting."
            running && carState == "on" -> "On: the car can open http://${CarModeVpn.ADDRESS}${if (port == 80) "" else ":$port"} over the phone's hotspot."
            running && carState == "needs permission" -> "Needs the VPN approval: switch Car mode off and on again."
            running && carState == "error" -> "Couldn't start car mode. Restart casting."
            else -> "Starts with casting. Android asks once to allow the VPN. Other VPNs (like Tailscale) pause while it's on."
        }
        carNote.setTextColor(if (carMode && running && carState != "on") WARN else MUTED)

        val touchOn = TouchService.instance != null
        select(rotChips, PhoneScreen.mode)
        select(dimChips, PhoneScreen.dim)
        select(fpsChips, StreamSettings.fps)
        select(edgeChips, StreamSettings.edge)
        select(qualityChips, StreamSettings.quality)
        tuneNote.text = (if (running) "Changes apply right away. " else "") +
            "Lower resolution and quality give a higher frame rate, less delay and less heat. " +
            "The stream can't go faster than the app on screen draws."

        val screenOn = PhoneScreen.mode != "off" || PhoneScreen.dim
        rotNote.text = when {
            !screenOn -> "The phone rotates and sets its brightness normally."
            !touchOn -> "Needs Touch control (below) to work."
            else -> listOfNotNull(
                if (PhoneScreen.mode != "off") "${if (running) "Holding" else "Holds"} ${PhoneScreen.mode}" else null,
                if (PhoneScreen.dim) "${if (running) "screen at" else "dims the screen to"} lowest brightness (the stream stays bright)" else null,
            ).joinToString(", ").replaceFirstChar { it.uppercase() } + if (running) "." else " while casting."
        }
        rotNote.setTextColor(if (screenOn && !touchOn) WARN else MUTED)
        touchRow.set(touchOn, if (touchOn) "Taps and swipes from the browser work" else "Tap to turn on in Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val audio = hasAudio()
        val audioStatus = CaptureService.audioStatus
        soundRow.set(
            audio && (!running || audioStatus == "on"),
            when {
                !audio -> "Tap to allow audio capture (app sound only, not the mic)"
                running && audioStatus == "needs permission" -> "Restart casting to turn sound on"
                running && audioStatus == "error" -> "Couldn't capture sound. Restart casting"
                running -> "Tap Sound in the viewer. Disconnect Bluetooth from the car to avoid an echo"
                else -> "Ready. Tap Sound in the viewer"
            }
        ) { if (!audio) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO) }

        val unrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryRow.set(unrestricted, if (unrestricted) "Android won't stop casting in the background" else "Tap to set DashCast to Unrestricted") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** The fixed car-mode address: the same on every hotspot, so it can be bookmarked once in the car. */
    private fun carRow(port: Int): View {
        val url = "http://${CarModeVpn.ADDRESS}${if (port == 80) "" else ":$port"}"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = pressable(Color.parseColor("#1F2A40"), 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DashCast", url))
                Toast.makeText(this@MainActivity, "Copied $url", Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(text(19f, TEXT, bold = true).apply {
            text = url
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })
        row.addView(text(13f, ACCENT).apply {
            text = "car mode · use this in the car (on the phone's hotspot)"
            setPadding(0, dp(2), 0, 0)
        })
        return row
    }

    /**
     * One address, shown two ways: a hostname (e.g. 192-168-43-1.sslip.io, a free wildcard DNS that resolves back
     * to the IP) for browsers that refuse bare IP addresses, and the plain IP. Port 80 needs no ":port".
     */
    private fun urlRow(iface: String, ip: String, port: Int, highlight: Boolean): View {
        val suffix = if (port == 80) "" else ":$port"
        val url = "http://${ip.replace('.', '-')}.sslip.io$suffix"
        val plain = "http://$ip$suffix"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = pressable(if (highlight) Color.parseColor("#1F2A40") else Color.parseColor("#22262E"), 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DashCast", url))
                Toast.makeText(this@MainActivity, "Copied $url", Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(text(19f, if (highlight) TEXT else MUTED, bold = highlight).apply {
            text = url
            typeface = Typeface.create(Typeface.MONOSPACE, if (highlight) Typeface.BOLD else Typeface.NORMAL)
        })
        row.addView(text(14f, MUTED).apply {
            text = "or $plain"
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, 0)
        })
        val label = if (HOTSPOT.containsMatchIn(iface)) "$iface · hotspot (use this one)" else "$iface · tap to copy"
        row.addView(text(13f, if (highlight) ACCENT else MUTED).apply { text = label; setPadding(0, dp(2), 0, 0) })
        return row
    }

    /** A checklist line: status badge, title, explanation; tappable when there's something to fix. */
    private inner class Row(parent: LinearLayout, title: String) {
        private val badge = text(14f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(14) }
        }
        private val sub = text(13f, MUTED)
        private val chevron = text(22f, MUTED).apply { text = "›"; setPadding(dp(8), 0, 0, 0) }
        private val view = LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            addView(badge)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(text(16f, TEXT).apply { text = title })
                addView(sub)
            })
            addView(chevron)
        }
        init { parent.addView(view) }

        fun set(ok: Boolean, detail: String, action: () -> Unit) {
            badge.text = if (ok) "✓" else "!"
            badge.background = oval(if (ok) OK else WARN)
            sub.text = detail
            chevron.visibility = if (ok) View.INVISIBLE else View.VISIBLE
            view.background = if (ok) null else pressable(Color.TRANSPARENT, 10)
            view.setOnClickListener(if (ok) null else View.OnClickListener { action() })
            view.isClickable = !ok
        }
    }

    /** A row of equal-width choices; [chips] collects them so refresh() can show the selection. */
    private fun <T> segmented(options: List<Pair<T, String>>, chips: MutableMap<T, TextView>, pick: (T) -> Unit) =
        LinearLayout(this).apply {
            background = rounded(Color.parseColor("#22262E"), 12)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            for ((value, name) in options) {
                val chip = text(15f, TEXT, bold = true).apply {
                    text = name
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(10))
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    setOnClickListener { pick(value); refresh() }
                }
                chips[value] = chip
                addView(chip)
            }
        }

    private fun <T> select(chips: Map<T, TextView>, value: T) {
        for ((v, chip) in chips) {
            val sel = v == value
            chip.background = if (sel) rounded(ACCENT, 10) else pressable(Color.TRANSPARENT, 10)
            chip.setTextColor(if (sel) Color.WHITE else MUTED)
        }
    }

    private fun label(t: String) = text(14f, TEXT).apply { text = t; setPadding(0, dp(14), 0, 0) }

    // ---- small view helpers ----
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun pressable(color: Int, radius: Int) =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(color, radius), rounded(Color.WHITE, radius))

    private fun card(title: String? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 18)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        if (title != null) addView(text(12f, MUTED, bold = true).apply { text = title; letterSpacing = 0.08f })
    }
}
