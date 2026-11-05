package com.eberhard.rokidmirror.glasses

import android.app.Activity
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/**
 * The glasses side: announces itself on the Wi-Fi, accepts the phone's connection, decodes the
 * H.264 stream and draws it on a SurfaceView fitted to the display. Nothing is stored.
 */
class MainActivity : Activity() {
    private lateinit var video: SurfaceView
    private lateinit var status: View
    private lateinit var line1: TextView
    private lateinit var line2: TextView
    private val main = Handler(Looper.getMainLooper())

    private var server: ServerSocket? = null
    private var serverThread: Thread? = null
    private var client: Socket? = null
    private var decoder: Decoder? = null
    private var surfaceReady = false
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        video = findViewById(R.id.video)
        status = findViewById(R.id.status)
        line1 = findViewById(R.id.line1)
        line2 = findViewById(R.id.line2)
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                decoder?.close()
                decoder = null
            }
        })
    }

    override fun onStart() {
        super.onStart()
        startServer()
        startDexProber()
        advertise()
        showWaiting()
    }

    override fun onStop() {
        unadvertise()
        stopDexProber()
        stopServer()
        super.onStop()
    }

    // ---- DeX mode: the phone (the hotspot gateway) serves a scrcpy stream of a virtual display
    // that Samsung DeX runs on; the glasses connect to it. Tried every few seconds while idle, so
    // there is nothing to press here: whichever of Mirror or DeX the phone offers is shown.
    @Volatile private var dexActive = false
    private var dexThread: Thread? = null

    private fun startDexProber() {
        if (dexThread != null) return
        val t = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
                if (client != null && !client!!.isClosed) continue // mirroring is on
                // Ask the network who runs the DeX screen; fall back to the hotspot gateway (the phone).
                val (host, port) = findDex() ?: (gatewayIp()?.let { it to Protocol.DEX_PORT } ?: continue)
                val socket = try {
                    Socket().apply { connect(java.net.InetSocketAddress(host, port), 1500) }
                } catch (e: Exception) { continue }
                dexActive = true
                main.post { line1.text = "DeX"; line2.text = "Connected to the phone's DeX screen, waiting for the picture…" }
                val why = runCatching { serveDex(socket) }.exceptionOrNull()
                runCatching { socket.close() }
                decoder?.close()
                decoder = null
                dexActive = false
                lastEnd = "DeX ended at ${now()}" + (why?.let { if (it is EOFException) "" else ": ${it.javaClass.simpleName} ${it.message}" } ?: "")
                showWaiting()
            }
        }, "rokid-screen-dex").apply { isDaemon = true }
        dexThread = t
        t.start()
    }

    private fun stopDexProber() {
        dexThread?.interrupt()
        dexThread = null
    }

    /**
     * scrcpy's video socket (server 5.x, send_device_meta=false, send_dummy_byte=false,
     * send_stream_meta=true, send_frame_meta=true): 4-byte codec id, then 12-byte headers.
     * A header whose first int has the top bit set is a session packet (flags, width, height);
     * otherwise it is a frame header (int64 pts+flags, int32 size) followed by the packet.
     */
    private fun serveDex(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 15000
        val input = DataInputStream(socket.getInputStream().buffered(256 * 1024))
        input.readInt() // codec id (h264)
        var first = true
        while (!socket.isClosed) {
            val a = input.readInt()
            val b = input.readInt()
            val c = input.readInt()
            if (a and Int.MIN_VALUE != 0) {
                // Session: a new display size (DeX started, or the display was resized).
                val w = b
                val h = c
                decoder?.close()
                main.post { video.visibility = View.VISIBLE }
                var waited = 0
                while (!surfaceReady && waited < 100) { Thread.sleep(50); waited++ }
                if (!surfaceReady) throw IllegalStateException("No drawing surface")
                fitSurface(w, h)
                decoder = Decoder(w, h, video.holder.surface) { if (first) { first = false; hideStatus() } }
                continue
            }
            val ptsAndFlags = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
            val size = c
            val payload = ByteArray(size).also { input.readFully(it) }
            val isConfig = ptsAndFlags and (1L shl 62) != 0L
            val pts = ptsAndFlags and ((1L shl 61) - 1)
            if (isConfig) decoder?.feed(payload, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            else decoder?.feed(payload, pts, 0)
        }
    }

    /** Broadcasts "RMDEX?" and waits a second for "RMDEX <port>" from the phone running rokid-dex.sh. */
    private fun findDex(): Pair<String, Int>? = runCatching {
        java.net.DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 1000
            val ask = "RMDEX?".toByteArray()
            socket.send(java.net.DatagramPacket(ask, ask.size, InetAddress.getByName("255.255.255.255"), Protocol.DEX_DISCOVERY_PORT))
            val buf = ByteArray(64)
            val packet = java.net.DatagramPacket(buf, buf.size)
            socket.receive(packet)
            val text = String(packet.data, 0, packet.length).trim()
            if (!text.startsWith("RMDEX ")) return null
            val host = packet.address.hostAddress ?: return null
            host to (text.removePrefix("RMDEX ").trim().toIntOrNull() ?: Protocol.DEX_PORT)
        }
    }.getOrNull()

    /** The phone, when the glasses are on its hotspot: the default route's gateway. */
    private fun gatewayIp(): String? = runCatching {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress
    }.getOrNull()

    /** Why the previous connection ended, kept on the waiting screen so it can be read. */
    @Volatile private var lastEnd: String? = null
    private fun now() = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    private fun showWaiting(detail: String? = null) = main.post {
        // Hiding the SurfaceView drops the last frame, so the waiting text sits on black.
        video.visibility = View.INVISIBLE
        status.visibility = View.VISIBLE
        line1.text = "Waiting for the phone…"
        line2.text = detail ?: "Open Rokid Mirror on the phone.\n" + (localIps().takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { (name, ip) -> "$name: $ip" } ?: "Not on Wi-Fi: join the phone's hotspot in Hi Rokid.") +
            (lastEnd?.let { "\n\n$it" } ?: "")
    }

    private fun showConnected(from: String) = main.post {
        line1.text = "Connected"
        line2.text = from
        // The overlay goes once the first frame is drawn.
    }

    private fun hideStatus() = main.post { status.visibility = View.GONE }

    private var probe: java.net.DatagramSocket? = null

    /** Answers the phone's UDP broadcast so it finds us even where mDNS doesn't cross the hotspot. */
    private fun startProbeAnswering() {
        if (probe != null) return
        val socket = runCatching { java.net.DatagramSocket(Protocol.PROBE_PORT).apply { broadcast = true } }.getOrNull() ?: return
        probe = socket
        Thread({
            val buf = ByteArray(64)
            while (!socket.isClosed) {
                val packet = java.net.DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (e: Exception) { break }
                if (String(packet.data, 0, packet.length).startsWith("RMDISC?")) {
                    val reply = "RMDISC ${Protocol.PORT}".toByteArray()
                    runCatching { socket.send(java.net.DatagramPacket(reply, reply.size, packet.address, packet.port)) }
                }
            }
        }, "rokid-screen-probe").apply { isDaemon = true; start() }
    }

    private fun startServer() {
        if (server != null) return
        startProbeAnswering()
        val s = ServerSocket(Protocol.PORT).apply { reuseAddress = true }
        server = s
        serverThread = Thread({
            while (!s.isClosed) {
                val socket = try { s.accept() } catch (e: Exception) { break }
                if (dexActive) { runCatching { socket.close() }; continue }
                // One phone at a time: a new connection replaces the old one.
                client?.let { runCatching { it.close() } }
                client = socket
                val why = runCatching { serve(socket) }.exceptionOrNull()
                runCatching { socket.close() }
                decoder?.close()
                decoder = null
                lastEnd = when (why) {
                    null, is EOFException -> "Phone stopped at ${now()}"
                    is java.net.SocketTimeoutException -> "Phone went silent at ${now()} (hotspot off, or Rokid Mirror closed)"
                    else -> "Lost the phone at ${now()}: ${why.javaClass.simpleName} ${why.message}"
                }
                showWaiting()
            }
        }, "rokid-screen-server").apply { isDaemon = true; start() }
    }

    private fun stopServer() {
        runCatching { probe?.close() }
        probe = null
        runCatching { client?.close() }
        runCatching { server?.close() }
        client = null
        server = null
        serverThread = null
        decoder?.close()
        decoder = null
    }

    private fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        // A phone that vanished (hotspot off, app killed) never sends a FIN over Wi-Fi; give up on silence.
        socket.soTimeout = Protocol.SILENCE_TIMEOUT_MS
        val input = DataInputStream(socket.getInputStream().buffered(256 * 1024))
        val magic = ByteArray(4).also { input.readFully(it) }
        if (String(magic) != Protocol.MAGIC) throw IllegalStateException("Not a Rokid Mirror stream")
        showConnected(socket.inetAddress.hostAddress ?: "phone")
        var first = true
        while (!socket.isClosed) {
            val kind = input.readInt()
            val length = input.readInt()
            val pts = input.readLong()
            val payload = ByteArray(length).also { input.readFully(it) }
            when (kind) {
                Protocol.KIND_PING -> {}
                Protocol.KIND_BYE -> return
                Protocol.KIND_HEADER -> {
                    val b = ByteBuffer.wrap(payload)
                    val w = b.int
                    val h = b.int
                    decoder?.close()
                    main.post { video.visibility = View.VISIBLE }
                    var waited = 0
                    while (!surfaceReady && waited < 100) { Thread.sleep(50); waited++ }
                    if (!surfaceReady) throw IllegalStateException("No drawing surface")
                    fitSurface(w, h)
                    main.post { line2.text = "Stream ${w}x$h, waiting for the first picture…" }
                    decoder = Decoder(w, h, video.holder.surface) { if (first) { first = false; hideStatus() } }
                }
                Protocol.KIND_CONFIG -> decoder?.feed(payload, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                Protocol.KIND_FRAME -> decoder?.feed(payload, pts, 0)
            }
        }
    }

    /** Letterbox: the stream's aspect inside the glasses' 480x640, centred. */
    private fun fitSurface(w: Int, h: Int) = main.post {
        val parent = video.parent as FrameLayout
        val pw = parent.width
        val ph = parent.height
        if (pw == 0 || ph == 0) return@post
        val scale = minOf(pw.toFloat() / w, ph.toFloat() / h)
        val lp = video.layoutParams as FrameLayout.LayoutParams
        lp.width = (w * scale).toInt()
        lp.height = (h * scale).toInt()
        lp.gravity = android.view.Gravity.CENTER
        video.layoutParams = lp
    }

    private fun advertise() {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("rokid-screen").apply { setReferenceCounted(false); acquire() }
        // Keep the glasses' Wi-Fi awake and responsive while we wait: a sleeping radio is a
        // "No route to host" for the phone.
        @Suppress("DEPRECATION")
        wifiLock = runCatching {
            wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "rokid-screen").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        val manager = getSystemService(NSD_SERVICE) as NsdManager
        nsd = manager
        val info = NsdServiceInfo().apply {
            serviceName = "Rokid Screen"
            serviceType = Protocol.SERVICE_TYPE
            port = Protocol.PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun unadvertise() {
        registration?.let { l -> runCatching { nsd?.unregisterService(l) } }
        registration = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    /** Every IPv4 address with its interface, Wi-Fi (wlan*) first: that's the one the phone needs. */
    private fun localIps(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filter { it is java.net.Inet4Address }.map { ni.name to it.hostAddress.orEmpty() } }
            .sortedBy { (name, _) -> if (name.startsWith("wlan")) 0 else 1 }
            .map { (name, ip) -> (if (name.startsWith("wlan")) "Wi-Fi" else name) to ip }
    }.getOrDefault(emptyList())

    /** An asynchronous H.264 decoder drawing straight to the surface, frames released as they come. */
    private class Decoder(w: Int, h: Int, surface: android.view.Surface, private val onFirstFrame: () -> Unit) {
        private class Packet(val bytes: ByteArray, val pts: Long, val flags: Int)

        private val queue = LinkedBlockingQueue<Packet>(60)
        private val codec: MediaCodec = MediaCodec.createDecoderByType("video/avc")
        @Volatile private var closed = false
        private var rendered = false

        init {
            val format = MediaFormat.createVideoFormat("video/avc", w, h).apply {
                if (android.os.Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
                    if (closed) return
                    val p = try { queue.take() } catch (e: InterruptedException) { return }
                    if (closed) return
                    val buf = c.getInputBuffer(index) ?: return
                    buf.clear()
                    buf.put(p.bytes)
                    runCatching { c.queueInputBuffer(index, 0, p.bytes.size, p.pts, p.flags) }
                }

                override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (closed) return
                    runCatching { c.releaseOutputBuffer(index, true) }
                    if (!rendered) { rendered = true; onFirstFrame() }
                }

                override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {}
                override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {}
            })
            codec.configure(format, surface, null, 0)
            codec.start()
        }

        fun feed(bytes: ByteArray, pts: Long, flags: Int) {
            if (closed) return
            // Live mirror: when we fall behind, drop the oldest queued frame rather than lag.
            if (!queue.offer(Packet(bytes, pts, flags))) {
                queue.poll()
                queue.offer(Packet(bytes, pts, flags))
            }
        }

        fun close() {
            closed = true
            queue.offer(Packet(ByteArray(0), 0, 0)) // unblock a waiting take()
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }
}
