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
        advertise()
        showWaiting()
    }

    override fun onStop() {
        unadvertise()
        stopServer()
        super.onStop()
    }

    private fun showWaiting(detail: String? = null) = main.post {
        status.visibility = View.VISIBLE
        line1.text = "Waiting for the phone…"
        line2.text = detail ?: "Open Rokid Mirror on the phone.\nGlasses IP: ${localIp() ?: "not on Wi-Fi"}  port ${Protocol.PORT}"
    }

    private fun showConnected(from: String) = main.post {
        line1.text = "Connected"
        line2.text = from
        // The overlay goes once the first frame is drawn.
    }

    private fun hideStatus() = main.post { status.visibility = View.GONE }

    private fun startServer() {
        if (server != null) return
        val s = ServerSocket(Protocol.PORT).apply { reuseAddress = true }
        server = s
        serverThread = Thread({
            while (!s.isClosed) {
                val socket = try { s.accept() } catch (e: Exception) { break }
                // One phone at a time: a new connection replaces the old one.
                client?.let { runCatching { it.close() } }
                client = socket
                runCatching { serve(socket) }
                    .onFailure { if (it !is EOFException) showWaiting("Lost the phone: ${it.message}") else showWaiting() }
                runCatching { socket.close() }
                decoder?.close()
                decoder = null
                showWaiting()
            }
        }, "rokid-screen-server").apply { isDaemon = true; start() }
    }

    private fun stopServer() {
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
                Protocol.KIND_HEADER -> {
                    val b = ByteBuffer.wrap(payload)
                    val w = b.int
                    val h = b.int
                    decoder?.close()
                    while (!surfaceReady) Thread.sleep(50)
                    fitSurface(w, h)
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
    }

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

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
