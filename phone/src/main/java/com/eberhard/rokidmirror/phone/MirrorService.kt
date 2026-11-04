package com.eberhard.rokidmirror.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Captures the phone screen (with the user's screen-recording consent), encodes it as H.264
 * at the glasses' size and streams it to Rokid Screen over TCP. Folding or unfolding restarts
 * the encoder at the new shape and sends a new stream header.
 */
class MirrorService : Service() {

    sealed interface State {
        data object Off : State
        data class Connecting(val host: String) : State
        data class Streaming(val host: String, val width: Int, val height: Int) : State
        data class Failed(val message: String) : State
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drain: Thread? = null
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var host = ""
    private var config: ByteArray? = null
    private var size = 0 to 0
    @Volatile private var running = false
    private val displayManager by lazy { getSystemService(DISPLAY_SERVICE) as DisplayManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                // People type what the glasses show, sometimes with ":7070" on the end: take just the address.
                host = intent.getStringExtra(EXTRA_HOST).orEmpty().trim().substringBefore(':').substringBefore('/')
                if (data == null || host.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground()
                start(code, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mirroring", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Mirroring to the glasses")
            .setContentText(host)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun start(code: Int, data: Intent) {
        _state.value = State.Connecting(host)
        running = true
        Thread({
            try {
                val s = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, Protocol.PORT), 5000)
                }
                socket = s
                val o = DataOutputStream(s.getOutputStream().buffered(256 * 1024))
                o.write(Protocol.MAGIC.toByteArray())
                o.flush()
                out = o
                main.post {
                    if (!running) return@post
                    // Anything the capture or encoder refuses is shown on the phone, not a crash.
                    try { beginCapture(code, data) } catch (e: Exception) { fail("Capture failed: ${e.javaClass.simpleName}: ${e.message}") }
                }
            } catch (e: Exception) {
                fail("Couldn't reach the glasses at $host: ${e.message}")
            }
        }, "rokid-mirror-connect").start()
    }

    private fun beginCapture(code: Int, data: Intent) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try { manager.getMediaProjection(code, data) } catch (e: Exception) { null }
        if (p == null) {
            fail("Screen capture wasn't allowed.")
            return
        }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopEverything(); stopSelf() }
        }, main)
        displayManager.registerDisplayListener(displayListener, main)
        val bounds = screenBounds()
        val (w, h) = targetSize(bounds)
        size = w to h
        val enc = newEncoder(w, h)
        encoder = enc
        inputSurface = enc.createInputSurface()
        enc.start()
        display = p.createVirtualDisplay(
            "RokidMirror", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, main,
        )
        sendHeader(w, h)
        startDrain(enc)
        _state.value = State.Streaming(host, w, h)
    }

    /** The phone was folded or unfolded: same projection, new size, new encoder. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !running) return
            val (w, h) = targetSize(screenBounds())
            if (w to h == size) return
            main.postDelayed({
                if (running && (w to h) != size) {
                    try { restartEncoder(w, h) } catch (e: Exception) { fail("Re-fit failed: ${e.message}") }
                }
            }, 300)
        }
    }

    private fun restartEncoder(w: Int, h: Int) {
        val d = display ?: return
        stopDrain()
        encoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        inputSurface?.release()
        val enc = newEncoder(w, h)
        encoder = enc
        inputSurface = enc.createInputSurface()
        enc.start()
        d.resize(w, h, resources.displayMetrics.densityDpi)
        d.surface = inputSurface
        size = w to h
        config = null
        sendHeader(w, h)
        startDrain(enc)
        _state.value = State.Streaming(host, w, h)
    }

    private fun newEncoder(w: Int, h: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat("video/avc", w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Default (VBR) rate control: some encoders refuse CBR at configure time.
        }
        return MediaCodec.createEncoderByType("video/avc").apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun screenBounds(): Rect {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return wm.maximumWindowMetrics.bounds
    }

    /** Fit the screen inside the glasses' 480x640, both sides a multiple of 16 for the encoder. */
    private fun targetSize(b: Rect): Pair<Int, Int> {
        val scale = minOf(GLASSES_W.toFloat() / b.width(), GLASSES_H.toFloat() / b.height())
        fun align(v: Float) = (v.toInt() / 16 * 16).coerceAtLeast(64)
        return align(b.width() * scale) to align(b.height() * scale)
    }

    private fun sendHeader(w: Int, h: Int) {
        val payload = ByteBuffer.allocate(8).putInt(w).putInt(h).array()
        send(Protocol.KIND_HEADER, payload, 0)
    }

    @Synchronized
    private fun send(kind: Int, payload: ByteArray, pts: Long): Boolean {
        val o = out ?: return false
        return try {
            o.writeInt(kind)
            o.writeInt(payload.size)
            o.writeLong(pts)
            o.write(payload)
            o.flush()
            true
        } catch (e: Exception) {
            fail("The glasses went away: ${e.message}")
            false
        }
    }

    private fun startDrain(enc: MediaCodec) {
        val t = Thread({
            val info = MediaCodec.BufferInfo()
            while (running && encoder === enc) {
                val index = try { enc.dequeueOutputBuffer(info, 20_000) } catch (e: Exception) { break }
                if (index < 0) continue
                val buf = enc.getOutputBuffer(index) ?: continue
                val bytes = ByteArray(info.size).also { buf.position(info.offset); buf.get(it) }
                enc.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = bytes
                    if (!send(Protocol.KIND_CONFIG, bytes, 0)) break
                } else {
                    if (!send(Protocol.KIND_FRAME, bytes, info.presentationTimeUs)) break
                }
            }
        }, "rokid-mirror-drain").apply { isDaemon = true }
        drain = t
        t.start()
    }

    private fun stopDrain() {
        val t = drain ?: return
        drain = null
        runCatching { t.join(500) }
    }

    private fun fail(message: String) {
        _state.value = State.Failed(message)
        main.post { stopEverything(); stopSelf() }
    }

    private fun stopEverything() {
        running = false
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        stopDrain()
        runCatching { display?.release() }
        display = null
        encoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        encoder = null
        inputSurface?.release()
        inputSurface = null
        runCatching { projection?.stop() }
        projection = null
        runCatching { out?.close() }
        runCatching { socket?.close() }
        out = null
        socket = null
        if (_state.value !is State.Failed) _state.value = State.Off
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"
        const val EXTRA_HOST = "host"
        const val GLASSES_W = 480
        const val GLASSES_H = 640
        private const val CHANNEL = "mirror"
        private const val NOTIFICATION_ID = 7

        private val _state = MutableStateFlow<State>(State.Off)
        val state: StateFlow<State> = _state.asStateFlow()

        fun start(context: Context, resultCode: Int, data: Intent, host: String) {
            val i = Intent(context, MirrorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_HOST, host)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP))
        }
    }
}
