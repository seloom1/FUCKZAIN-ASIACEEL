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
    val excludedApps: Set<String> = emptySet(),
    val publicIp: String? = null, val countryName: String? = null,
    val countryCode: String? = null, val ipLoading: Boolean = false
) { val current get() = servers.getOrNull(selected) }

object Vpn {
    val state = MutableStateFlow(UiState())
    @Volatile var stopRequested = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statsJob: Job? = null
    private var ipJob: Job? = null
    private fun prefs(c: Context) = c.getSharedPreferences("vpn", 0)

    fun load(c: Context) {
        val raws = prefs(c).getString("links", "")!!.split("\u0001").filter { it.isNotBlank() }
        state.value = state.value.copy(servers = raws.mapNotNull(ConfigParser::parse),
            selected = prefs(c).getInt("sel", 0),
            excludedApps = prefs(c).getStringSet("excluded_apps", emptySet()).orEmpty().toSet())
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
        val s = state.value.current ?: run { state.value = state.value.copy(error = "أضف رابطاً أولاً"); return }
        val config = ConfigParser.buildConfig(s)
        c.getSharedPreferences("vpn_runtime", Context.MODE_PRIVATE).edit()
            .putString("active_config", config).apply()
        stopRequested = false
        state.value = state.value.copy(connected = false, connecting = true, error = null,
            publicIp = null, countryName = null, countryCode = null, ipLoading = true)
        val intent = Intent(c, SingBoxService::class.java).putExtra("config", config)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(intent)
        else c.startService(intent)
    }

    fun stop(c: Context) {
        stopRequested = true
        c.getSharedPreferences("vpn_runtime", Context.MODE_PRIVATE).edit().remove("active_config").apply()
        SingBoxService.stopNow()
        c.stopService(Intent(c, SingBoxService::class.java))
        onDisconnected()
    }

    fun onConnected() {
        state.value = state.value.copy(connected = true, connecting = false, ipLoading = true,
            publicIp = null, countryName = null, countryCode = null)
        statsJob?.cancel()
        statsJob = scope.launch {
            val uid = Process.myUid(); var tx = TrafficStats.getUidTxBytes(uid); var rx = TrafficStats.getUidRxBytes(uid)
            launch { measurePing() }
            while (isActive) {
                delay(1000)
                val ntx = TrafficStats.getUidTxBytes(uid); val nrx = TrafficStats.getUidRxBytes(uid)
                // نصف الحركة تقريباً: الحزم تُحسب داخل النفق وخارجه
                state.value = state.value.copy(upKB = (ntx - tx) / 2048, downKB = (nrx - rx) / 2048)
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
        statsJob?.cancel()
        ipJob?.cancel()
        state.value = state.value.copy(connected = false, connecting = false, upKB = 0, downKB = 0,
            pingMs = -1, error = error, publicIp = null, countryName = null, countryCode = null, ipLoading = false)
    }

    private data class ExitLocation(val ip: String?, val countryName: String?, val countryCode: String?)

    /** Route the lookup through sing-box's loopback proxy while the app UID remains excluded from TUN. */
    private fun fetchExitLocation(): ExitLocation? {
        var connection: HttpURLConnection? = null
        return try {
            val localProxy = Proxy(Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", ConfigParser.IP_CHECK_PROXY_PORT))
            connection = (URL("https://ipapi.co/json/").openConnection(localProxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "FUCKZAIN-ASIACEEL Android")
            }
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("error", false)) return null
            fun field(key: String) = json.optString(key).takeUnless { it.isBlank() || it == "null" }
            ExitLocation(
                ip = field("ip"),
                countryName = field("country_name"),
                countryCode = field("country_code") ?: field("country")
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
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
