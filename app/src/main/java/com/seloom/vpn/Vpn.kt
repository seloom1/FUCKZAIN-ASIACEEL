package com.seloom.vpn

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.Socket
import java.net.URL

data class UiState(
    val connected: Boolean = false, val connecting: Boolean = false,
    val servers: List<Server> = emptyList(), val selected: Int = 0,
    val upKB: Long = 0, val downKB: Long = 0, val pingMs: Int = -1, val error: String? = null,
    val totalUpKB: Long = 0, val totalDownKB: Long = 0,
    val excludedApps: Set<String> = emptySet(),
    val publicIp: String? = null, val countryName: String? = null,
    val countryCode: String? = null, val ipLoading: Boolean = false
) { val current get() = servers.getOrNull(selected) }

object Vpn {
    private const val DEFAULT_WARP = "wireguard://98qlf4cSnq2VMmonjeWZI1dS1994IzfbR%2FfRdG%2FiCoE%3D@engage.cloudflareclient.com:2408?address=172.16.0.2%2F32%2C2606%3A4700%3A110%3A8d70%3A8df1%3A6e3d%3A693b%3Aea40%2F128&publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D&privatekey=98qlf4cSnq2VMmonjeWZI1dS1994IzfbR%2FfRdG%2FiCoE%3D#seloom1warp"
    val state = MutableStateFlow(UiState())
    @Volatile var stopRequested = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statsJob: Job? = null
    private var ipJob: Job? = null
    private var appContext: Context? = null
    private var sessionStartedAt: Long? = null
    private var sessionServerName = "—"
    private var sessionProtocol = "—"
    private fun prefs(c: Context) = c.getSharedPreferences("vpn", 0)

    private fun log(message: String) {
        appContext?.let { DiagnosticLog.record(it, message) }
    }

    fun load(c: Context) {
        appContext = c.applicationContext
        val stored = prefs(c).getString("links", "")!!.split("\u0001").filter { it.isNotBlank() }
        val raws = if (stored.any { it.startsWith("wireguard://") }) stored else listOf(DEFAULT_WARP) + stored
        state.value = state.value.copy(servers = raws.mapNotNull(ConfigParser::parse),
            selected = prefs(c).getInt("sel", 0),
            excludedApps = prefs(c).getStringSet("excluded_apps", emptySet()).orEmpty().toSet())
        if (raws != stored) save(c)
    }

    private fun save(c: Context) = prefs(c).edit()
        .putString("links", state.value.servers.joinToString("\u0001") { it.raw })
        .putInt("sel", state.value.selected).apply()

    /** يضيف روابط من نص (سطر لكل رابط). يعيد عدد ما أُضيف. */
    fun import(c: Context, text: String): Int {
        val chunks = if (text.contains("[Interface]")) listOf(text) else text.lines()
        val added = chunks.mapNotNull(ConfigParser::parse)
        state.value = state.value.copy(servers = state.value.servers + added)
        save(c); return added.size
    }

    fun select(c: Context, i: Int) { state.value = state.value.copy(selected = i); save(c) }
    fun remove(c: Context, i: Int) {
        state.value = state.value.copy(servers = state.value.servers.filterIndexed { k, _ -> k != i },
            selected = 0); save(c)
    }

    fun editServer(c: Context, i: Int, name: String, host: String, port: Int, format: String): Boolean {
        if (host.isBlank() || port !in 1..65535 || format.isBlank()) return false
        val current = state.value.servers.getOrNull(i) ?: return false
        val updated = ConfigParser.edit(current, name, host, port, format) ?: return false
        state.value = state.value.copy(servers = state.value.servers.mapIndexed { index, server ->
            if (index == i) updated else server
        })
        save(c)
        return true
    }

    fun editVless(c: Context, i: Int, name: String, host: String, port: Int, uuid: String, flow: String,
                  security: String, sni: String, alpn: String, insecure: Boolean, fingerprint: String,
                  realityPublicKey: String, realityShortId: String, transportType: String,
                  pathOrService: String, transportHost: String): Boolean {
        val current = state.value.servers.getOrNull(i) ?: return false
        val updated = ConfigParser.editVless(current, name, host, port, uuid, flow, security, sni, alpn,
            insecure, fingerprint, realityPublicKey, realityShortId, transportType, pathOrService, transportHost)
            ?: return false
        state.value = state.value.copy(servers = state.value.servers.mapIndexed { index, server ->
            if (index == i) updated else server
        })
        save(c)
        return true
    }

    fun clearAll(c: Context) {
        state.value = state.value.copy(servers = emptyList(), selected = 0, error = null)
        save(c)
    }

    fun saveExcludedApps(c: Context, packages: Set<String>) {
        val safePackages = packages.filter { it != c.packageName }.toSet()
        prefs(c).edit().putStringSet("excluded_apps", safePackages.toMutableSet()).apply()
        state.value = state.value.copy(excludedApps = safePackages)
    }

    fun start(c: Context) {
        appContext = c.applicationContext
        val s = state.value.current ?: run {
            log("فشل البدء: لا يوجد سيرفر محدد")
            state.value = state.value.copy(error = "أضف رابطاً أولاً")
            return
        }
        log("بدء الاتصال: ${s.protocol} — ${s.host}:${s.port}")
        val config = ConfigParser.buildConfig(s)
        c.getSharedPreferences("vpn_runtime", Context.MODE_PRIVATE).edit()
            .putString("active_config", config).apply()
        stopRequested = false
        state.value = state.value.copy(connected = false, connecting = true, error = null,
            publicIp = null, countryName = null, countryCode = null, ipLoading = true,
            totalUpKB = 0, totalDownKB = 0)
        val intent = Intent(c, SingBoxService::class.java).putExtra("config", config)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(intent)
        else c.startService(intent)
    }

    fun stop(c: Context) {
        appContext = c.applicationContext
        log("طلب فصل يدوي")
        stopRequested = true
        c.getSharedPreferences("vpn_runtime", Context.MODE_PRIVATE).edit().remove("active_config").apply()
        SingBoxService.stopNow()
        c.stopService(Intent(c, SingBoxService::class.java))
        onDisconnected()
    }

    fun onConnected() {
        log("تم تشغيل النفق بنجاح")
        sessionStartedAt = System.currentTimeMillis()
        sessionServerName = state.value.current?.name ?: "—"
        sessionProtocol = state.value.current?.protocol ?: "—"
        state.value = state.value.copy(connected = true, connecting = false, ipLoading = true,
            publicIp = null, countryName = null, countryCode = null)
        statsJob?.cancel()
        statsJob = scope.launch {
            val uid = Process.myUid(); var tx = TrafficStats.getUidTxBytes(uid); var rx = TrafficStats.getUidRxBytes(uid)
            var totalTx = 0L; var totalRx = 0L
            launch {
                while (isActive) {
                    measurePing()
                    delay(4_000)
                }
            }
            while (isActive) {
                delay(1000)
                val ntx = TrafficStats.getUidTxBytes(uid); val nrx = TrafficStats.getUidRxBytes(uid)
                val deltaTx = (ntx - tx).coerceAtLeast(0); val deltaRx = (nrx - rx).coerceAtLeast(0)
                totalTx += deltaTx; totalRx += deltaRx
                state.value = state.value.copy(upKB = deltaTx / 1024, downKB = deltaRx / 1024,
                    totalUpKB = totalTx / 1024, totalDownKB = totalRx / 1024)
                tx = ntx; rx = nrx
            }
        }
        ipJob?.cancel()
        ipJob = scope.launch {
            val location = withContext(Dispatchers.IO) { fetchExitLocation() }
            if (state.value.connected) {
                state.value = state.value.copy(
                    publicIp = location?.ip,
                    countryName = location?.countryName,
                    countryCode = location?.countryCode,
                    ipLoading = false
                )
            }
        }
    }

    fun onDisconnected(error: String? = null) {
        log(if (error.isNullOrBlank()) "انقطع النفق" else "انقطع النفق بسبب: ${error.take(180)}")
        sessionStartedAt?.let { startedAt ->
            appContext?.let { context ->
                UsageHistory.add(context, UsageSession(
                    startedAt = startedAt,
                    endedAt = System.currentTimeMillis(),
                    serverName = sessionServerName,
                    protocol = sessionProtocol,
                    uploadedKB = state.value.totalUpKB,
                    downloadedKB = state.value.totalDownKB
                ))
            }
        }
        sessionStartedAt = null
        statsJob?.cancel()
        ipJob?.cancel()
        state.value = state.value.copy(connected = false, connecting = false, upKB = 0, downKB = 0,
            pingMs = -1, error = error, publicIp = null, countryName = null, countryCode = null, ipLoading = false)
    }

    private data class ExitLocation(val ip: String?, val countryName: String?, val countryCode: String?)

    /** Route the lookup through sing-box's loopback proxy while the app UID remains excluded from TUN. */
    private fun fetchExitLocation(): ExitLocation? {
        return try {
            // api4.ipify forces the result to IPv4; the previous generic endpoint
            // could return a long IPv6 address on IPv6-preferred networks.
            val ipJson = requestJson("https://api4.ipify.org?format=json") ?: return null
            val ip = ipJson.optString("ip").takeIf { it.contains('.') } ?: return null
            val geo = requestJson("https://ipapi.co/$ip/json/")
            fun field(json: JSONObject?, key: String) = json?.optString(key)
                ?.takeUnless { it.isBlank() || it == "null" }
            ExitLocation(
                ip = ip,
                countryName = field(geo, "country_name"),
                countryCode = field(geo, "country_code") ?: field(geo, "country")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun requestJson(address: String): JSONObject? {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", ConfigParser.IP_CHECK_PROXY_PORT))
        val connection = (URL(address).openConnection(proxy) as HttpURLConnection)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "FUCKZAIN-ASIACEEL Android")
            if (connection.responseCode !in 200..299) null
            else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun measurePing() {
        val s = state.value.current ?: return
        val t = System.nanoTime()
        val ms = try { Socket().use { SingBoxService.protectProbeSocket(it); it.connect(InetSocketAddress(s.host, s.port), 3000) }
            ((System.nanoTime() - t) / 1_000_000).toInt() } catch (e: Exception) { -1 }
        state.value = state.value.copy(pingMs = ms)
    }
}
