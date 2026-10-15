package dev.dashcast

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mirrors the phone screen to browsers on the local network.
 * Frames are JPEG-encoded and drawn on a <canvas> in the viewer, so no <video> element is used.
 * App audio (media/game) is captured with AudioPlaybackCapture and streamed as PCM.
 *
 * Frame pipeline: capture thread copies the newest screen image into a "staged" bitmap; up to
 * [ENCODERS] threads take the staged frame when the viewer has room and the fps budget allows.
 * A frame that arrives while everything is busy is kept (not dropped), so the browser always
 * ends up showing the latest screen.
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "dev.dashcast.STOP"
        /** Port 80 lets browsers use a plain address with no ":port"; 8080 is the fallback. */
        val PORTS = listOf(80, 8080)
        /** The port in use while casting (or the one we expect to get when not casting). */
        @Volatile var port = 80
            private set
        /** True if Android refused port 80 and we fell back. */
        @Volatile var portFallback = false
            private set
        private const val ENCODERS = 3           // parallel JPEG encoders (about 25 ms each at 960x480)
        private const val AUDIO_RATE = 48000
        private const val AUDIO_CHUNK = AUDIO_RATE / 50 * 4   // 20 ms of 16-bit stereo
        private const val CHANNEL = "cast"
        private const val NOTIF_ID = 1
        @Volatile var running = false
            private set
        @Volatile var viewers = 0
            private set
        @Volatile var audioStatus = "off"
            private set
        /** The running service, so app settings can be applied live. */
        @Volatile var current: CaptureService? = null
            private set
    }

    private class Frame(val bmp: Bitmap, val w: Int, val h: Int)

    private val tag = "CaptureService"
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val encoders: ExecutorService = Executors.newFixedThreadPool(ENCODERS)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var server: FrameServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    // From StreamSettings (the app); the viewer can override them with ?q=&fps=&edge= in its URL.
    @Volatile private var maxEdge = 960
    @Volatile private var quality = 55
    @Volatile private var maxFps = 30
    private var capW = 0
    private var capH = 0

    // Guarded by lock.
    private val lock = Any()
    private val spare = ArrayList<Bitmap>()
    private var staged: Frame? = null
    private var busy = 0
    private var seq = 0L
    private var lastTakenNs = 0L
    private var pumpScheduled = false

    private val jpegOut = ThreadLocal.withInitial { ByteArrayOutputStream(256 * 1024) }
    private var padBuffer: ByteBuffer? = null

    // Stats, reset every second.
    private val statCaptured = AtomicInteger()
    private val statEncoded = AtomicInteger()
    private val statEncodeNs = AtomicLong()
    private val statBytes = AtomicLong()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("capture", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        handler = Handler(thread.looper)
    }

    private fun hasAudioPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }   // notification button
        // Must be in the foreground (type mediaProjection) before obtaining the projection.
        // The microphone type lets audio capture keep working once another app is in front (Android 11+).
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= 30 && hasAudioPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIF_ID, buildNotification(), type)

        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null || projection != null) {
            if (projection == null) stopSelf()
            return START_NOT_STICKY
        }

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val proj = mgr.getMediaProjection(code, data) ?: run { stopSelf(); return START_NOT_STICKY }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)
        projection = proj

        StreamSettings.load(this)
        maxEdge = StreamSettings.edge; quality = StreamSettings.quality; maxFps = StreamSettings.fps
        startServer()
        acquireLocks()
        startCapture()
        startAudio(proj)
        running = true
        current = this
        if (CarModeVpn.enabled(this)) CarModeVpn.start(this)
        PhoneScreen.load(this)
        PhoneScreen.apply()
        handler.postDelayed(statsTick, 1000)
        return START_NOT_STICKY
    }

    private fun startServer() {
        val html = assets.open("index.html").use { it.readBytes() }
        server = FrameServer(PORTS, html, { msg -> handleMessage(msg) }, { pump() }).also {
            try {
                it.start()
                port = it.port
                portFallback = it.port != PORTS.first()
            } catch (e: Exception) { Log.e(tag, "server failed", e); stopSelf() }
        }
    }

    private fun handleMessage(o: JSONObject) {
        try {
            when (o.optString("type")) {
                "g" -> { // gesture: normalised points 0..1 plus duration in ms
                    val arr = o.getJSONArray("p")
                    val pts = ArrayList<FloatArray>(arr.length())
                    for (i in 0 until arr.length()) {
                        val p = arr.getJSONArray(i)
                        pts.add(floatArrayOf(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()))
                    }
                    TouchService.instance?.gesture(pts, o.optLong("d", 60))
                }
                "k" -> TouchService.instance?.globalKey(o.optString("a"))
                "rot" -> PhoneScreen.set(this, o.optString("m"))   // off | landscape | portrait
                // tuning from the viewer URL, e.g. ?q=50&fps=30&edge=800
                "cfg" -> tune(o.optInt("q", quality), o.optInt("fps", maxFps), o.optInt("edge", maxEdge))
            }
        } catch (e: Exception) {
            Log.w(tag, "bad message: $o")
        }
    }

    fun tune(q: Int, fps: Int, edge: Int) {
        quality = q.coerceIn(20, 95)
        maxFps = fps.coerceIn(5, 60)
        val e = edge.coerceIn(320, 2400)
        if (e != maxEdge) { maxEdge = e; handler.post { resizeCapture() } }
        Log.i(tag, "config q=$quality fps=$maxFps edge=$maxEdge")
    }

    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        return dm
    }

    private fun computeSize(): Triple<Int, Int, Int> {
        val dm = realMetrics()
        val s = min(1f, maxEdge.toFloat() / max(dm.widthPixels, dm.heightPixels))
        // Width a multiple of 64 so the ImageReader rows have no padding (no per-frame crop copy);
        // height follows the screen's aspect ratio and is kept even.
        val w = max(64, (dm.widthPixels * s).roundToInt() / 64 * 64)
        val h = ((w.toFloat() * dm.heightPixels / dm.widthPixels).roundToInt() / 2) * 2
        return Triple(w, h, dm.densityDpi)
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).also {
            it.setOnImageAvailableListener({ r -> onImage(r) }, handler)
        }

    private fun startCapture() {
        val (w, h, dpi) = computeSize()
        capW = w; capH = h
        val r = newReader(w, h)
        reader = r
        display = projection?.createVirtualDisplay(
            "DashCast", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
        Log.i(tag, "capturing ${w}x$h")
    }

    /** Rotation (e.g. an app going landscape) or a new size from the viewer: resize the capture. */
    private fun resizeCapture() {
        val (w, h, dpi) = computeSize()
        if (w == capW && h == capH) return
        val old = reader
        val r = newReader(w, h)
        reader = r
        capW = w; capH = h
        synchronized(lock) { spare.clear(); staged = null }
        display?.resize(w, h, dpi)
        display?.surface = r.surface
        old?.close()
        Log.i(tag, "capturing ${w}x$h")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post { resizeCapture() }
    }

    private fun obtainBitmap(w: Int, h: Int): Bitmap = synchronized(lock) {
        while (spare.isNotEmpty()) {
            val b = spare.removeAt(spare.size - 1)
            if (b.width == w && b.height == h) return b
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    /** Capture thread: copy the newest screen image into a staged bitmap, then try to send it. */
    private fun onImage(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val srv = server ?: return
            viewers = srv.clientCount()
            if (viewers == 0) return

            val plane = image.planes[0]
            val bufW = plane.rowStride / plane.pixelStride
            val bmp = obtainBitmap(bufW, image.height)
            val src = plane.buffer
            src.rewind()
            if (src.remaining() >= bmp.byteCount) {
                bmp.copyPixelsFromBuffer(src)
            } else {
                // Some drivers don't pad the last row; pad it so the copy has enough bytes.
                val pad = padBuffer?.takeIf { it.capacity() >= bmp.byteCount }
                    ?: ByteBuffer.allocateDirect(bmp.byteCount).also { padBuffer = it }
                pad.clear(); pad.put(src); pad.rewind()
                bmp.copyPixelsFromBuffer(pad)
            }
            statCaptured.incrementAndGet()
            synchronized(lock) {
                staged?.let { spare.add(it.bmp) }   // superseded before it was sent
                staged = Frame(bmp, image.width, image.height)
            }
            pump()
        } catch (e: Exception) {
            Log.w(tag, "capture failed: ${e.message}")
        } finally {
            image.close()
        }
    }

    /** Hand the staged frame to an encoder if one is free, a viewer has room and the fps budget allows. */
    private fun pump() {
        val srv = server ?: return
        val frame: Frame
        val mySeq: Long
        synchronized(lock) {
            val f = staged ?: return
            if (busy >= ENCODERS || !srv.wantsFrame(busy)) return       // retried on ack / encode done
            val now = System.nanoTime()
            // 2 ms slack: a 60 Hz screen delivers frames every 16.7 ms with some jitter.
            val waitNs = lastTakenNs + 1_000_000_000L / maxFps - 2_000_000L - now
            if (waitNs > 0) {
                if (!pumpScheduled) {
                    pumpScheduled = true
                    handler.postDelayed({ synchronized(lock) { pumpScheduled = false }; pump() },
                        waitNs / 1_000_000L + 1)
                }
                return
            }
            staged = null
            busy++
            lastTakenNs = now
            mySeq = ++seq
            frame = f
        }
        try {
            encoders.execute { encode(frame, mySeq) }
        } catch (e: Exception) { // shutting down
            synchronized(lock) { busy-- }
        }
    }

    private fun encode(f: Frame, mySeq: Long) {
        try {
            val t0 = System.nanoTime()
            val src = if (f.bmp.width != f.w || f.bmp.height != f.h) Bitmap.createBitmap(f.bmp, 0, 0, f.w, f.h) else f.bmp
            val out = jpegOut.get()!!
            out.reset()
            src.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (src !== f.bmp) src.recycle()
            val jpeg = out.toByteArray()
            statEncodeNs.addAndGet(System.nanoTime() - t0)
            statEncoded.incrementAndGet()
            statBytes.addAndGet(jpeg.size.toLong())
            server?.broadcast(mySeq, jpeg)
        } catch (e: Exception) {
            Log.w(tag, "encode failed: ${e.message}")
        } finally {
            synchronized(lock) {
                busy--
                if (spare.size < 4) spare.add(f.bmp)
            }
            pump()
        }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            val srv = server ?: return
            val enc = statEncoded.getAndSet(0)
            val json = JSONObject()
                .put("type", "stat")
                .put("cap", statCaptured.getAndSet(0))
                .put("enc", enc)
                .put("encMs", if (enc > 0) statEncodeNs.getAndSet(0) / enc / 1_000_000.0 else 0.0)
                .put("kb", if (enc > 0) statBytes.getAndSet(0) / enc / 1024 else 0)
                .put("w", capW).put("h", capH)
                .put("q", quality).put("fps", maxFps)
                .put("audio", audioStatus)
                .put("rot", PhoneScreen.mode)
                .put("touch", TouchService.instance != null)
            if (enc == 0) { statEncodeNs.set(0); statBytes.set(0) }
            viewers = srv.clientCount()
            if (viewers > 0) srv.broadcastText(json.toString())
            if (enc > 0) Log.d(tag, json.toString())
            handler.postDelayed(this, 1000)
        }
    }

    /**
     * Captures what other apps play (media, games, "unknown" usage) so the browser can play it.
     * Calls (voice communication) and apps that opt out of capture can't be recorded by Android.
     */
    private fun startAudio(proj: MediaProjection) {
        if (!hasAudioPermission()) { audioStatus = "needs permission"; return }
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AUDIO_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("MissingPermission")
            val rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(max(minBuf, AUDIO_CHUNK * 4))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            rec.startRecording()
            audioRecord = rec
            audioStatus = "on"
            audioThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                while (true) {
                    val buf = ByteArray(AUDIO_CHUNK)
                    var off = 0
                    while (off < buf.size) {
                        val n = rec.read(buf, off, buf.size - off)
                        if (n < 0) return@Thread   // released on stop
                        off += n
                    }
                    val srv = server ?: return@Thread
                    if (srv.audioClientCount() > 0) srv.broadcastAudio(buf)
                }
            }, "audio").also { it.start() }
            Log.i(tag, "audio capture started")
        } catch (e: Exception) {
            audioStatus = "error"
            Log.e(tag, "audio capture failed", e)
        }
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DashCast:cast").also { it.acquire() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "DashCast:wifi").also { it.acquire() }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW))
        }
        // Tapping the notification brings DashCast back to the front (reusing the open screen if there is one).
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("DashCast is casting")
            .setContentText("Tap to open DashCast and see the address to open in a browser")
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop casting", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        current = null
        CarModeVpn.stop(this)
        PhoneScreen.apply()   // releases the lock
        viewers = 0
        audioStatus = "off"
        handler.removeCallbacksAndMessages(null)
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        server?.stop()
        server = null
        encoders.shutdownNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        thread.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
