package com.eberhard.rokidmirror.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Finds "Rokid Screen" on the Wi-Fi (or the phone's own hotspot) by mDNS. */
class Discovery(context: Context) {
    data class Found(val name: String, val host: String, val port: Int)

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found.asStateFlow()

    private var probe: Thread? = null

    /**
     * mDNS doesn't always cross a phone's hotspot, so the glasses also answer a UDP broadcast:
     * "RMDISC?" to port 7071, reply "RMDISC <port>" from their address.
     */
    private fun startProbe() {
        val t = Thread({
            try {
                java.net.DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 1000
                    val ask = "RMDISC?".toByteArray()
                    val buf = ByteArray(64)
                    while (!Thread.currentThread().isInterrupted) {
                        runCatching {
                            socket.send(java.net.DatagramPacket(ask, ask.size, java.net.InetAddress.getByName("255.255.255.255"), Protocol.PROBE_PORT))
                        }
                        val until = System.currentTimeMillis() + 2000
                        while (System.currentTimeMillis() < until) {
                            val packet = java.net.DatagramPacket(buf, buf.size)
                            try { socket.receive(packet) } catch (e: java.net.SocketTimeoutException) { continue }
                            val text = String(packet.data, 0, packet.length)
                            if (!text.startsWith("RMDISC ")) continue
                            val host = packet.address.hostAddress ?: continue
                            val port = text.removePrefix("RMDISC ").trim().toIntOrNull() ?: Protocol.PORT
                            _found.value = _found.value.filterNot { it.host == host } + Found("Rokid Screen", host, port)
                        }
                    }
                }
            } catch (e: Exception) { /* no network: nothing to find */ }
        }, "rokid-mirror-probe").apply { isDaemon = true }
        probe = t
        t.start()
    }

    fun start() {
        if (listener != null) return
        startProbe()
        lock = wifi.createMulticastLock("rokid-mirror").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(
                    service,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            val entry = Found(info.serviceName, host, info.port)
                            _found.value = (_found.value.filterNot { it.host == host } + entry)
                        }
                    },
                )
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                _found.value = _found.value.filterNot { it.name == service.serviceName }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stop() {
        probe?.interrupt()
        probe = null
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        runCatching { lock?.release() }
        lock = null
    }
}
