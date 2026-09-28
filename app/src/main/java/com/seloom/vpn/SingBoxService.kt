package com.seloom.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.*

/**
 * خدمة الـ VPN. تشغّل نواة sing-box عبر libbox وتعطيها واصف TUN.
 * متوافق مع واجهة PlatformInterface التي يولدها sing-box 1.10.7.
 */
class SingBoxService : VpnService() {
    companion object {
        const val ACTION_STOP = "com.seloom.vpn.action.STOP"
        private const val CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 1
        @Volatile private var active: SingBoxService? = null

        fun stopNow() {
            active?.let {
                it.stopping = true
                it.shutdown()
                it.stopSelf()
            }
        }

        fun protectProbeSocket(socket: java.net.Socket) {
            active?.protect(socket)
        }
    }

    private var box: BoxService? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var tunDupPfd: ParcelFileDescriptor? = null
    @Volatile private var stopping = false
    private val interfaceCallbacks = mutableMapOf<InterfaceUpdateListener, ConnectivityManager.NetworkCallback>()

    override fun onCreate() {
        super.onCreate()
        active = this
        if (Vpn.state.value.servers.isEmpty()) Vpn.load(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DiagnosticLog.record(this, "استلام أمر إيقاف الخدمة")
            stopping = true
            Vpn.stopRequested = true
            shutdown()
            return START_NOT_STICKY
        }
        val runtimePrefs = getSharedPreferences("vpn_runtime", MODE_PRIVATE)
        val config = intent?.getStringExtra("config") ?: runtimePrefs.getString("active_config", null)
            ?: return START_NOT_STICKY
        DiagnosticLog.record(this, "بدء خدمة sing-box")
        runtimePrefs.edit().putString("active_config", config).apply()
        stopping = false
        Vpn.stopRequested = false
        Vpn.state.value = Vpn.state.value.copy(connecting = true, error = null)
        box?.let { runCatching { it.close() } }
        box = null
        startForeground(NOTIFICATION_ID, notification())
        Thread {
            try {
                val base = filesDir.absolutePath; val cache = cacheDir.absolutePath
                Libbox.setup(SetupOptions().apply {
                    basePath = base
                    workingPath = base
                    tempPath = cache
                    fixAndroidStack = true
                })
                if (stopping || Vpn.stopRequested) return@Thread
                val service = Libbox.newService(config, Platform(this))
                if (stopping || Vpn.stopRequested) { service.close(); return@Thread }
                box = service
                service.start()
                if (stopping || Vpn.stopRequested) shutdown() else Vpn.onConnected()
            } catch (e: Exception) {
                DiagnosticLog.record(this, "خطأ sing-box: ${(e.message ?: "استثناء غير معروف").take(180)}")
                if (!stopping) shutdown(e.message ?: "فشل الاتصال")
            }
        }.start()
        return START_STICKY
    }

    @Synchronized
    private fun shutdown(error: String? = null) {
        if (error != null) DiagnosticLog.record(this, "إغلاق الخدمة بسبب: ${error.take(180)}")
        stopping = true
        val cm = getSystemService(ConnectivityManager::class.java)
        synchronized(interfaceCallbacks) {
            interfaceCallbacks.values.forEach { runCatching { cm?.unregisterNetworkCallback(it) } }
            interfaceCallbacks.clear()
        }
        val pfd = tunPfd
        tunPfd = null
        val dupPfd = tunDupPfd
        tunDupPfd = null
        // Revoke Android's VPN session first. This makes the system status-bar
        // VPN indicator disappear even if libbox is still unwinding a read.
        try { pfd?.close() } catch (_: Exception) {}
        try { dupPfd?.close() } catch (_: Exception) {}
        try { box?.close() } catch (_: Exception) {}
        box = null
        getSharedPreferences("vpn_runtime", MODE_PRIVATE).edit().remove("active_config").apply()
        Vpn.stopRequested = true
        if (active === this) active = null
        Vpn.onDisconnected(error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        @Suppress("DEPRECATION")
        stopForeground(true)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onRevoke() = shutdown()
    override fun onDestroy() { shutdown(); super.onDestroy() }

    private fun networkCallback(listener: InterfaceUpdateListener) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publishNetworkInterface(listener, network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = publishNetworkInterface(listener, network)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
            publishNetworkInterface(listener, network, linkProperties.interfaceName)
        }
        override fun onLost(network: Network) {
            runCatching { listener.updateDefaultInterface("", -1) }
        }
    }

    private fun publishNetworkInterface(listener: InterfaceUpdateListener, network: Network, knownName: String? = null) {
        if (stopping) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val name = knownName ?: cm.getLinkProperties(network)?.interfaceName ?: return
        val index = runCatching { java.net.NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        if (index >= 0) runCatching { listener.updateDefaultInterface(name, index) }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "FUCK ASIACEEL & ZAIN", NotificationManager.IMPORTANCE_LOW))
        }
        val stopIntent = Intent(this, SingBoxService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(this, 100, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("FUCK ASIACEEL & ZAIN")
            .setContentText("يعمل في الخلفية — اضغط إيقاف لإنهاء الاتصال")
            .setSmallIcon(android.R.drawable.ic_lock_lock).setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "إيقاف", stopPendingIntent).build())
            .build()
    }

    /** الجسر بين النواة ونظام أندرويد */
    class Platform(private val svc: SingBoxService) : PlatformInterface {
        override fun openTun(o: TunOptions): Int {
            val b = svc.Builder().setSession("FUCK ASIACEEL & ZAIN").setMtu(o.mtu)
            val a4 = o.inet4Address
            while (a4.hasNext()) { val p = a4.next(); b.addAddress(p.address(), p.prefix()) }
            // Keep the Android VPN profile IPv4-only. Some Android builds reject
            // adding the generated IPv6 ULA address with VpnService.Builder.
            b.addRoute("0.0.0.0", 0)
            b.addDnsServer(o.dnsServerAddress.value)
            // Preserve the original working behavior: the VPN app's own UID must not loop into TUN.
            b.addDisallowedApplication(svc.packageName)
            val excludedApps = svc.getSharedPreferences("vpn", Context.MODE_PRIVATE)
                .getStringSet("excluded_apps", emptySet()).orEmpty()
            excludedApps.filter { it != svc.packageName }.forEach { packageName ->
                runCatching { b.addDisallowedApplication(packageName) }
                    .onFailure { Log.w("SeloomVPN", "Unable to exclude app $packageName", it) }
            }
            val pfd = b.establish() ?: error("تعذّر إنشاء النفق")
            svc.tunPfd?.close()
            svc.tunPfd = pfd
            // Keep the original descriptor so closing it explicitly revokes the
            // Android VPN indicator; pass a retained duplicate to libbox for I/O.
            svc.tunDupPfd?.close()
            svc.tunDupPfd = ParcelFileDescriptor.dup(pfd.fileDescriptor)
            return svc.tunDupPfd!!.fd
        }
        override fun autoDetectInterfaceControl(fd: Int) { svc.protect(fd) }
        override fun usePlatformAutoDetectInterfaceControl() = true
        override fun writeLog(message: String) { Log.i("SeloomVPN", message) }
        override fun useProcFS() = false
        override fun underNetworkExtension() = false
        override fun includeAllNetworks() = false
        override fun clearDNSCache() {}
        // Android supplies the protected socket path; do not let sing-box
        // create its Linux netlink monitor in an ordinary application.
        override fun usePlatformDefaultInterfaceMonitor() = true
        override fun usePlatformInterfaceGetter() = false
        override fun startDefaultInterfaceMonitor(l: InterfaceUpdateListener) {
            val cm = svc.getSystemService(ConnectivityManager::class.java) ?: return
            synchronized(svc.interfaceCallbacks) {
                if (svc.interfaceCallbacks.containsKey(l)) return
                val callback = svc.networkCallback(l)
                svc.interfaceCallbacks[l] = callback
                runCatching {
                    cm.registerNetworkCallback(
                        NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                            .build(),
                        callback
                    )
                }.onFailure {
                    svc.interfaceCallbacks.remove(l)
                    Log.w("SeloomVPN", "Unable to monitor default network", it)
                }
            }
        }
        override fun closeDefaultInterfaceMonitor(l: InterfaceUpdateListener) {
            val cm = svc.getSystemService(ConnectivityManager::class.java) ?: return
            synchronized(svc.interfaceCallbacks) {
                svc.interfaceCallbacks.remove(l)?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            }
        }
        override fun getInterfaces(): NetworkInterfaceIterator = object : NetworkInterfaceIterator {
            override fun hasNext() = false
            override fun next() = NetworkInterface()
        }
        override fun findConnectionOwner(p: Int, s: String, sp: Int, d: String, dp: Int): Int = -1
        override fun packageNameByUid(uid: Int): String = ""
        override fun uidByPackageName(n: String): Int = -1
        override fun readWIFIState(): WIFIState? = null
        override fun sendNotification(n: io.nekohasekai.libbox.Notification) {}
    }
}
