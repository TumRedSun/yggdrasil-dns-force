package eu.neilalexander.yggdrasil

import android.content.Intent
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import eu.neilalexander.yggdrasil.YggStateReceiver.Companion.YGG_STATE_INTENT
import mobile.Yggdrasil
import org.json.JSONArray
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread


private const val TAG = "PacketTunnelProvider"
const val SERVICE_NOTIFICATION_ID = 1000

// Default upstream DNS server — user's dnsmasq on the home server.
// Can be overridden in the DNS settings screen via KEY_FORCE_DNS_UPSTREAM.
const val DEFAULT_FORCE_DNS_UPSTREAM = "20b:14e7:9d48:1c5f:490:56b1:6e5a:6ee9"

open class PacketTunnelProvider: VpnService() {
    companion object {
        const val STATE_INTENT = "eu.neilalexander.yggdrasil.PacketTunnelProvider.STATE_MESSAGE"

        const val ACTION_START = "eu.neilalexander.yggdrasil.PacketTunnelProvider.START"
        const val ACTION_STOP = "eu.neilalexander.yggdrasil.PacketTunnelProvider.STOP"
        const val ACTION_TOGGLE = "eu.neilalexander.yggdrasil.PacketTunnelProvider.TOGGLE"
        const val ACTION_CONNECT = "eu.neilalexander.yggdrasil.PacketTunnelProvider.CONNECT"
    }

    private var yggdrasil = Yggdrasil()
    private var started = AtomicBoolean()

    private lateinit var config: ConfigurationProxy

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var updateThread: Thread? = null
    private var dnsProxyThread: Thread? = null
    private var dnsProxy: DnsProxy? = null

    private var parcel: ParcelFileDescriptor? = null
    private var readerStream: FileInputStream? = null
    private var writerStream: FileOutputStream? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        config = ConfigurationProxy(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        stop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.d(TAG, "Intent is null")
            return START_NOT_STICKY
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val enabled = preferences.getBoolean(PREF_KEY_ENABLED, false)
        return when (intent.action ?: ACTION_STOP) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping...")
                stop(); START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                Log.d(TAG, "Connecting...")
                if (started.get()) {
                    connect()
                } else {
                    start()
                }
                START_STICKY
            }
            ACTION_TOGGLE -> {
                Log.d(TAG, "Toggling...")
                if (started.get()) {
                    stop(); START_NOT_STICKY
                } else {
                    start(); START_STICKY
                }
            }
            else -> {
                if (!enabled) {
                    Log.d(TAG, "Service is disabled")
                    return START_NOT_STICKY
                }
                Log.d(TAG, "Starting...")
                start(); START_STICKY
            }
        }
    }

    private fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        val notification = createServiceNotification(this, State.Enabled)
        startForeground(SERVICE_NOTIFICATION_ID, notification)

        // Acquire multicast lock
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("Yggdrasil").apply {
            setReferenceCounted(false)
            acquire()
        }

        Log.d(TAG, config.getJSON().toString())
        yggdrasil.startJSON(config.getJSONByteArray())

        val address = yggdrasil.addressString
        val builder = Builder()
            .addAddress(address, 7)
            .addRoute("200::", 7)
            // We do this to trick the DNS-resolver into thinking that we have "regular" IPv6,
            // and therefore we need to resolve AAAA DNS-records.
            // See: https://android.googlesource.com/platform/bionic/+/refs/heads/master/libc/dns/net/getaddrinfo.c#1935
            // and: https://android.googlesource.com/platform/bionic/+/refs/heads/master/libc/dns/net/getaddrinfo.c#365
            // If we don't do this the DNS-resolver just doesn't do DNS-requests with record type AAAA,
            // and we can't use DNS with Yggdrasil addresses.
            .addRoute("2000::", 128)
            .allowFamily(OsConstants.AF_INET)
            .setBlocking(true)
            .setMtu(yggdrasil.mtu.toInt())
            .setSession("Yggdrasil")
        // On Android API 29+ apps can opt-in/out to using metered networks.
        // If we don't set metered status of VPN it is considered as metered.
        // If we set it to false, then it will inherit this status from underlying network.
        // See: https://developer.android.com/reference/android/net/VpnService.Builder#setMetered(boolean)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)

        // ---- MODIFICATION START ----
        // "Force system DNS" mode: starts a local DNS proxy on the phone's Yggdrasil
        // address (port 53) that forwards every query to a user-configured upstream
        // DNS server (default = home dnsmasq on the Yggdrasil network). The local
        // proxy address is then registered with VpnService.addDnsServer() so that
        // *every* app on the phone — including Element, SchildiChat, Termux —
        // resolves hostnames via the user's dnsmasq, bypassing both the carrier
        // DNS and Android 13's "Private DNS" (DoT) mechanism which otherwise
        // ignores VpnService DNS settings.
        //
        // NOTE: allowBypass() is intentionally NOT called when force DNS is on,
        // because allowBypass() lets privileged apps skip the VPN entirely,
        // which defeats the purpose of forcing DNS through the tunnel.
        val forceDnsEnabled = preferences.getBoolean(KEY_FORCE_SYSTEM_DNS, true)
        val forceDnsUpstream = preferences.getString(
            KEY_FORCE_DNS_UPSTREAM, DEFAULT_FORCE_DNS_UPSTREAM
        ) ?: DEFAULT_FORCE_DNS_UPSTREAM

        if (forceDnsEnabled && forceDnsUpstream.isNotEmpty()) {
            Log.i(TAG, "Force system DNS enabled, upstream=$forceDnsUpstream")
            try {
                dnsProxy = DnsProxy(
                    localAddress = address,
                    upstream = forceDnsUpstream
                )
                dnsProxy?.start()
                // Tell Android that the phone's own Yggdrasil IP is the DNS server.
                // Queries to this IP never leave the device — they are handled by
                // our local DnsProxy, which then forwards them through the tunnel.
                builder.addDnsServer(address)
                Log.i(TAG, "Local DNS proxy started on $address:53 -> $forceDnsUpstream:53")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start local DNS proxy: ${e.message}", e)
                // Fall back to the old behaviour (forward to upstream directly).
                builder.addDnsServer(forceDnsUpstream)
            }
        } else {
            // Legacy behaviour: forward directly to the configured DNS servers.
            val serverString = preferences.getString(KEY_DNS_SERVERS, "")
            if (serverString!!.isNotEmpty()) {
                val servers = serverString.split(",")
                if (servers.isNotEmpty()) {
                    servers.forEach {
                        Log.i(TAG, "Using DNS server $it")
                        builder.addDnsServer(it)
                    }
                }
            }
        }
        // ---- MODIFICATION END ----

        if (preferences.getBoolean(KEY_ENABLE_CHROME_FIX, false)) {
            builder.addRoute("2001:4860:4860::8888", 128)
        }

        parcel = builder.establish()
        val parcel = parcel
        if (parcel == null || !parcel.fileDescriptor.valid()) {
            stop()
            return
        }

        readerStream = FileInputStream(parcel.fileDescriptor)
        writerStream = FileOutputStream(parcel.fileDescriptor)

        readerThread = thread {
            reader()
        }
        writerThread = thread {
            writer()
        }
        updateThread = thread {
            updater()
        }

        var intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_ENABLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun stop() {
        if (!started.compareAndSet(true, false)) {
            return
        }

        yggdrasil.stop()

        dnsProxy?.stop()
        dnsProxy = null
        dnsProxyThread?.let {
            it.interrupt()
            dnsProxyThread = null
        }

        readerStream?.let {
            it.close()
            readerStream = null
        }
        writerStream?.let {
            it.close()
            writerStream = null
        }
        parcel?.let {
            it.close()
            parcel = null
        }

        readerThread?.let {
            it.interrupt()
            readerThread = null
        }
        writerThread?.let {
            it.interrupt()
            writerThread = null
        }
        updateThread?.let {
            it.interrupt()
            updateThread = null
        }

        var intent = Intent(STATE_INTENT)
        intent.putExtra("type", "state")
        intent.putExtra("started", false)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_DISABLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        stopForeground(true)
        stopSelf()
        multicastLock?.release()
    }

    private fun connect() {
        if (!started.get()) {
            return
        }
        yggdrasil.retryPeersNow()
    }

    private fun updater() {
        try {
            Thread.sleep(500)
        } catch (_: InterruptedException) {
            return
        }
        var lastStateUpdate = System.currentTimeMillis()
        updates@ while (started.get()) {
            val treeJSON = yggdrasil.treeJSON
            if ((application as  GlobalApplication).needUiUpdates()) {
                val intent = Intent(STATE_INTENT)
                intent.putExtra("type", "state")
                intent.putExtra("started", true)
                intent.putExtra("ip", yggdrasil.addressString)
                intent.putExtra("subnet", yggdrasil.subnetString)
                intent.putExtra("pubkey", yggdrasil.publicKeyString)
                intent.putExtra("peers", yggdrasil.peersJSON)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
            val curTime = System.currentTimeMillis()
            if (lastStateUpdate + 10000 < curTime) {
                val intent = Intent(YGG_STATE_INTENT)
                var state = STATE_ENABLED
                if (yggdrasil.routingEntries > 0) {
                    state = STATE_CONNECTED
                }
                if (treeJSON != null && treeJSON != "null") {
                    val treeState = JSONArray(treeJSON)
                    val count = treeState.length()
                    if (count > 1)
                        state = STATE_CONNECTED
                }
                intent.putExtra("state", state)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                lastStateUpdate = curTime
            }

            if (Thread.currentThread().isInterrupted) {
                break@updates
            }
            if (sleep()) return
        }
    }

    private fun sleep(): Boolean {
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            return true
        }
        return false
    }

    private fun writer() {
        val buf = ByteArray(65535)
        writes@ while (started.get()) {
            val writerStream = writerStream
            val writerThread = writerThread
            if (writerThread == null || writerStream == null) {
                Log.i(TAG, "Write thread or stream is null")
                break@writes
            }
            if (Thread.currentThread().isInterrupted || !writerStream.fd.valid()) {
                Log.i(TAG, "Write thread interrupted or file descriptor is invalid")
                break@writes
            }
            try {
                val len = yggdrasil.recvBuffer(buf)
                if (len > 0) {
                    writerStream.write(buf, 0, len.toInt())
                }
            } catch (e: Exception) {
                Log.i(TAG, "Error in write: $e")
                if (e.toString().contains("ENOBUFS")) {
                    //TODO Check this by some error code
                    //More info about this: https://github.com/AdguardTeam/AdguardForAndroid/issues/724
                    continue
                }
                break@writes
            }
        }
        writerStream?.let {
            it.close()
            writerStream = null
        }
    }

    private fun reader() {
        val b = ByteArray(65535)
        reads@ while (started.get()) {
            val readerStream = readerStream
            val readerThread = readerThread
            if (readerThread == null || readerStream == null) {
                Log.i(TAG, "Read thread or stream is null")
                break@reads
            }
            if (Thread.currentThread().isInterrupted ||!readerStream.fd.valid()) {
                Log.i(TAG, "Read thread interrupted or file descriptor is invalid")
                break@reads
            }
            try {
                val n = readerStream.read(b)
                yggdrasil.sendBuffer(b, n.toLong())
            } catch (e: Exception) {
                Log.i(TAG, "Error in sendBuffer: $e")
                break@reads
            }
        }
        readerStream?.let {
            it.close()
            readerStream = null
        }
    }

    /**
     * A small UDP DNS forwarder that listens on the phone's own Yggdrasil
     * address (port 53) and forwards every datagram to an upstream DNS
     * server (typically the user's dnsmasq on the Yggdrasil network).
     *
     * Why this exists:
     *   Android 13's "Private DNS" (DoT) feature intercepts DNS queries at
     *   the resolver level *before* VpnService.addDnsServer() can redirect
     *   them, so configuring a Yggdrasil-side DNS server via the standard
     *   VpnService API is silently ignored for most apps (Chrome works only
     *   because the Yggdrasil app has a separate "trick Chrome" hack).
     *
     *   By running a local UDP DNS proxy on the phone's Yggdrasil IP and
     *   registering that IP via addDnsServer(), Android's resolver sends
     *   queries to our local proxy. Our proxy then forwards them through
     *   the Yggdrasil tunnel to the upstream dnsmasq, receives the reply,
     *   and sends it back to the local resolver.
     *
     * Routing notes:
     *   - The listening socket is bound to the phone's Yggdrasil IP, which
     *     is assigned to the TUN via addAddress(). Packets from the system
     *     resolver to that IP are local delivery (the IP is on a local
     *     interface), so they reach the proxy without entering the TUN.
     *   - The per-query outbound socket is NOT protected via protect().
     *     VpnService apps' unprotected sockets are routed through the VPN
     *     by default, so the outbound packet enters the TUN, is handled by
     *     the Yggdrasil Go runtime, and reaches the upstream dnsmasq over
     *     the Yggdrasil network. (The Go runtime's own peer sockets are
     *     separately protected by the Go code to avoid routing loops.)
     *
     * Why only UDP:
     *   - The Android system resolver uses UDP for almost everything; TCP
     *     is only used as a fallback for truncated responses.
     *   - Keeping the implementation UDP-only makes the proxy small and
     *     easy to audit. If a response has the TC (truncated) bit set,
     *     the resolver will retry over TCP against the same IP:53 — we
     *     don't currently handle TCP, but dnsmasq typically keeps UDP
     *     responses under 512 bytes by using EDNS0.
     */
    private inner class DnsProxy(
        private val localAddress: String,
        private val upstream: String
    ) {
        private val socket = DatagramSocket(null)
        private var running = AtomicBoolean(false)
        private var forwarderThread: Thread? = null

        fun start() {
            running.set(true)
            val local = InetSocketAddress(localAddress, 53)
            socket.bind(local)
            Log.i(TAG, "DnsProxy listening on $local")

            forwarderThread = thread(name = "DnsProxy-Forwarder") {
                forwarder()
            }
        }

        fun stop() {
            running.set(false)
            try { socket.close() } catch (_: Exception) {}
            forwarderThread?.interrupt()
        }

        private fun forwarder() {
            val recvBuf = ByteArray(4096)
            val upstreamAddr = InetAddress.getByName(upstream)
            Log.i(TAG, "DnsProxy forwarder ready, upstream=$upstreamAddr")

            while (running.get()) {
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                try {
                    socket.receive(recvPacket)
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.w(TAG, "DnsProxy receive error: ${e.message}")
                    }
                    continue
                }

                val queryLen = recvPacket.length
                if (queryLen < 12) continue  // too small to be a DNS packet

                // Make a copy of the query payload — recvBuf will be reused.
                val query = ByteArray(queryLen)
                System.arraycopy(recvBuf, 0, query, 0, queryLen)

                val clientAddr = recvPacket.address
                val clientPort = recvPacket.port

                // Forward the query to the upstream server asynchronously
                // so a single slow query doesn't block the proxy.
                thread(name = "DnsProxy-Query") {
                    try {
                        val fwdPacket = DatagramPacket(query, query.size, upstreamAddr, 53)
                        val respBuf = ByteArray(4096)
                        val respPacket = DatagramPacket(respBuf, respBuf.size)
                        // Synchronous send+receive on a fresh socket per query.
                        // This is less efficient than a multiplexed forwarder
                        // but it's simple and robust for a home network.
                        val qSock = DatagramSocket()
                        qSock.soTimeout = 4000
                        try {
                            qSock.send(fwdPacket)
                            qSock.receive(respPacket)
                            val respLen = respPacket.length
                            val resp = ByteArray(respLen)
                            System.arraycopy(respBuf, 0, resp, 0, respLen)
                            val reply = DatagramPacket(resp, resp.size, clientAddr, clientPort)
                            socket.send(reply)
                        } finally {
                            qSock.close()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "DnsProxy query forward failed: ${e.message}")
                    }
                }
            }
        }
    }
}
