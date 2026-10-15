package dev.dashcast

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal HTTP + WebSocket server.
 *  GET /     -> the viewer page (index.html from assets)
 *  GET /ws   -> WebSocket. Server -> browser binary messages start with one kind byte:
 *                 0x01 = JPEG frame, 0x02 = PCM audio (s16le, 48 kHz, stereo).
 *               Server -> browser text messages are JSON status ({"type":"stat",...}).
 *               Browser -> server: "ack" after drawing each frame (flow control),
 *               {"type":"audio","on":bool}, and JSON touch / config messages.
 */
class FrameServer(
    /** Tried in order; the first one Android lets us bind is used (see [port]). */
    private val ports: List<Int>,
    private val indexHtml: ByteArray,
    private val onMessage: (JSONObject) -> Unit,
    private val onAck: () -> Unit,
) {
    companion object {
        /** Frames being encoded or sent but not yet drawn by the browser. 3 keeps up with a 60 fps source. */
        const val MAX_IN_FLIGHT = 3
        const val KIND_VIDEO = 0x01
        const val KIND_AUDIO = 0x02
        /** Audio chunks queued per viewer before we start dropping (about 200 ms). */
        private const val MAX_AUDIO_QUEUED = 10
    }

    private val tag = "FrameServer"
    private val clients = CopyOnWriteArrayList<Client>()
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var lastFrame: ByteArray? = null
    @Volatile private var lastSeq = 0L

    /** The port actually listening, after [start]. */
    @Volatile var port = 0
        private set

    fun start() {
        var last: Exception? = null
        for (p in ports) {
            val ss = ServerSocket()
            try {
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p))
            } catch (e: Exception) {
                // Ports below 1024 need a privilege most Android versions don't give apps.
                Log.i(tag, "can't listen on $p: ${e.message}")
                try { ss.close() } catch (_: Exception) {}
                last = e
                continue
            }
            serverSocket = ss
            port = p
            pool.execute { acceptLoop(ss) }
            Log.i(tag, "listening on $p")
            return
        }
        throw last ?: IllegalStateException("no port to listen on")
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        clients.forEach { it.close() }
        clients.clear()
        pool.shutdownNow()
    }

    /** True if a viewer can take another frame, counting [pending] frames still being encoded. */
    fun wantsFrame(pending: Int): Boolean {
        val now = System.currentTimeMillis()
        return clients.any { it.recoverIfStuck(now); it.inFlight.get() + pending < MAX_IN_FLIGHT }
    }

    fun clientCount(): Int = clients.size

    fun audioClientCount(): Int = clients.count { it.audio }

    /**
     * Send frame [seq] to every viewer with room for it. Encoders run in parallel, so a frame that
     * finishes after a newer one is dropped. Viewers that are full get the newest frame on their next ack.
     */
    @Synchronized fun broadcast(seq: Long, jpeg: ByteArray) {
        if (seq <= lastSeq) return
        lastSeq = seq
        lastFrame = jpeg
        for (c in clients) {
            if (c.canTake()) c.sendVideo(seq, jpeg) else c.stale = true
        }
    }

    fun broadcastAudio(pcm: ByteArray) {
        for (c in clients) if (c.audio) c.sendAudio(pcm)
    }

    fun broadcastText(text: String) {
        val data = text.toByteArray()
        for (c in clients) c.writer.execute { c.sendFrame(0x1, -1, data) }
    }

    /** Called after an ack: catch up a viewer that missed the newest frame while it was full. */
    @Synchronized private fun catchUp(c: Client) {
        val f = lastFrame ?: return
        if (c.stale && lastSeq > c.sentSeq && c.canTake()) c.sendVideo(lastSeq, f)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val sock = try { ss.accept() } catch (e: SocketException) { break }
            sock.tcpNoDelay = true
            pool.execute { handle(sock) }
        }
    }

    private fun handle(sock: Socket) {
        try {
            val input = BufferedInputStream(sock.getInputStream())
            val out = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
            val requestLine = readLine(input) ?: return sock.close()
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            val isWs = headers["upgrade"]?.equals("websocket", true) == true
            when {
                isWs && path.startsWith("/ws") -> upgrade(sock, input, out, headers["sec-websocket-key"])
                path == "/" || path.startsWith("/?") || path.startsWith("/index") -> {
                    respond(out, "200 OK", "text/html; charset=utf-8", indexHtml); sock.close()
                }
                else -> { respond(out, "404 Not Found", "text/plain", "not found".toByteArray()); sock.close() }
            }
        } catch (e: Exception) {
            Log.d(tag, "connection ended: ${e.message}")
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun respond(out: OutputStream, status: String, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray()); out.write(body); out.flush()
    }

    private fun upgrade(sock: Socket, input: InputStream, out: OutputStream, key: String?) {
        if (key == null) { sock.close(); return }
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            Base64.NO_WRAP
        )
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
            "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
        out.flush()
        try { sock.sendBufferSize = 512 * 1024 } catch (_: Exception) {}
        val client = Client(sock, out)
        clients.add(client)
        synchronized(this) { lastFrame?.let { client.sendVideo(lastSeq, it) } }
        try {
            readFrames(input, client)
        } finally {
            clients.remove(client)
            client.close()
        }
    }

    private fun readFrames(input: InputStream, client: Client) {
        while (true) {
            val b0 = input.read(); if (b0 < 0) return
            val b1 = input.read(); if (b1 < 0) return
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((readByte(input) shl 8) or readByte(input)).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or readByte(input).toLong() } }
            if (len > 1_000_000) return // not expected from the viewer
            val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
            val payload = ByteArray(len.toInt()).also { readFully(input, it) }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                0x1 -> { // text
                    val text = String(payload, Charsets.UTF_8)
                    if (text == "ack") {
                        client.acked()
                        catchUp(client)
                        onAck()
                    } else {
                        val o = try { JSONObject(text) } catch (e: Exception) { null }
                        when {
                            o == null -> Log.w(tag, "bad message: $text")
                            o.optString("type") == "audio" -> client.audio = o.optBoolean("on")
                            else -> onMessage(o)
                        }
                    }
                }
                0x8 -> return                           // close
                0x9 -> client.sendFrame(0xA, -1, payload) // ping -> pong
                else -> {}                               // ignore binary/continuation/pong
            }
        }
    }

    private fun readByte(input: InputStream): Int { val b = input.read(); if (b < 0) throw EOFException(); return b }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) { val n = input.read(buf, off, buf.size - off); if (n < 0) throw EOFException(); off += n }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    inner class Client(private val sock: Socket, private val out: OutputStream) {
        /** Frames sent but not yet drawn by the browser. */
        val inFlight = AtomicInteger(0)
        @Volatile var lastAck = System.currentTimeMillis()
        @Volatile var sentSeq = 0L
        @Volatile var stale = false
        @Volatile var audio = false
        private val audioQueued = AtomicInteger(0)
        val writer: ExecutorService = Executors.newSingleThreadExecutor()   // keeps messages in order

        fun canTake() = inFlight.get() < MAX_IN_FLIGHT

        /** Recover if acks got lost: reset after 1.5 s without one. */
        fun recoverIfStuck(now: Long) {
            if (inFlight.get() > 0 && now - lastAck > 1500) { inFlight.set(0); lastAck = now }
        }

        fun acked() {
            lastAck = System.currentTimeMillis()
            inFlight.updateAndGet { if (it > 0) it - 1 else 0 }
        }

        fun sendVideo(seq: Long, jpeg: ByteArray) {
            inFlight.incrementAndGet()
            sentSeq = seq
            stale = false
            writer.execute { sendFrame(0x2, KIND_VIDEO, jpeg) }
        }

        fun sendAudio(pcm: ByteArray) {
            // A slow link must not build up audio delay: drop chunks rather than queue them.
            if (audioQueued.get() >= MAX_AUDIO_QUEUED) return
            audioQueued.incrementAndGet()
            writer.execute { try { sendFrame(0x2, KIND_AUDIO, pcm) } finally { audioQueued.decrementAndGet() } }
        }

        /** Writes one WebSocket frame; [kind] >= 0 is prepended to the payload as a type byte. */
        @Synchronized fun sendFrame(opcode: Int, kind: Int, data: ByteArray) {
            try {
                val size = data.size + if (kind >= 0) 1 else 0
                out.write(0x80 or opcode)
                when {
                    size < 126 -> out.write(size)
                    size < 65536 -> { out.write(126); out.write(size shr 8); out.write(size and 0xFF) }
                    else -> { out.write(127); for (s in 56 downTo 0 step 8) out.write(((size.toLong() shr s) and 0xFF).toInt()) }
                }
                if (kind >= 0) out.write(kind)
                out.write(data)
                out.flush()
            } catch (e: Exception) {
                close()
            }
        }

        fun close() {
            clients.remove(this)
            writer.shutdownNow()
            try { sock.close() } catch (_: Exception) {}
        }
    }
}
