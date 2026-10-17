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
    val countryCode: String? = null, val ipLoading: Boolean = false,
    val localDnsEnabled: Boolean = false,
    val subscriptions: List<String> = emptyList(),
    val subscriptionNames: Map<String, String> = emptyMap(),
    val manualServers: List<Server> = emptyList(),
    val subscriptionServers: Map<String, List<Server>> = emptyMap(),
    val upBps: Long = 0, val downBps: Long = 0,
    val bypassLanRouteEnabled: Boolean = false
) { val current get() = servers.getOrNull(selected) }

object Vpn {
    private const val DEFAULT_WARP = "wireguard://98qlf4cSnq2VMmonjeWZI1dS1994IzfbR%2FfRdG%2FiCoE%3D@engage.cloudflareclient.com:2408?address=172.16.0.2%2F32%2C2606%3A4700%3A110%3A8d70%3A8df1%3A6e3d%3A693b%3Aea40%2F128&publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D&privatekey=98qlf4cSnq2VMmonjeWZI1dS1994IzfbR%2FfRdG%2FiCoE%3D#seloom1warp"
    val state = MutableStateFlow(UiState())
    @Volatile var stopRequested = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statsJob: Job? = null
    private var ipJob: Job? = null
    private var settingsRestartJob: Job? = null
    private var appContext: Context? = null
    private var manualLinks: List<String> = emptyList()
    private var subscriptionLinks: LinkedHashMap<String, List<String>> = linkedMapOf()
    private var subscriptionNames: LinkedHashMap<String, String> = linkedMapOf()
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    @Volatile private var reconnectEnabled = false
    private var sessionStartedAt: Long? = null
    private var sessionServerName = "—"
    private var sessionProtocol = "—"
    private fun prefs(c: Context) = c.getSharedPreferences("vpn", 0)

    private fun log(message: String) {
        appContext?.let { DiagnosticLog.record(it, message) }
    }

    fun load(c: Context) {
        appContext = c.applicationContext
        val preferences = prefs(c)
        val stored = preferences.getString("links", "").orEmpty().split("\u0001").filter { it.isNotBlank() }
        val localDnsEnabled = if (!preferences.getBoolean("local_dns_default_migrated_v107", false)) {
            preferences.edit().putBoolean("local_dns_enabled", true)
                .putBoolean("local_dns_default_migrated_v107", true).apply()
            true
        } else preferences.getBoolean("local_dns_enabled", true)
        subscriptionLinks = decodeSubscriptions(preferences.getString("server_subscriptions", null))
        subscriptionNames = decodeSubscriptionNames(preferences.getString("server_subscription_names", null))
        subscriptionNames.keys.retainAll(subscriptionLinks.keys)
        val subscribedRaw = subscriptionLinks.values.flatten().toSet()
        manualLinks = if (preferences.contains("manual_links")) {
            preferences.getString("manual_links", "").orEmpty().split("\u0001").filter { it.isNotBlank() }
        } else stored.filterNot { it in subscribedRaw }
        if ((manualLinks + subscriptionLinks.values.flatten()).none { it.startsWith("wireguard://") })
            manualLinks = listOf(DEFAULT_WARP) + manualLinks
        val raws = (manualLinks + subscriptionLinks.values.flatten()).distinct()
        val servers = raws.mapNotNull(ConfigParser::parse)
        state.value = state.value.copy(servers = servers,
            selected = preferences.getInt("sel", 0).coerceIn(0, (servers.size - 1).coerceAtLeast(0)),
            subscriptions = subscriptionLinks.keys.toList(),
            subscriptionNames = LinkedHashMap(subscriptionNames),
            manualServers = manualLinks.mapNotNull(ConfigParser::parse),
            subscriptionServers = subscriptionLinks.mapValues { (_, links) -> links.mapNotNull(ConfigParser::parse) },
            excludedApps = prefs(c).getStringSet("excluded_apps", emptySet()).orEmpty().toSet(),
            localDnsEnabled = localDnsEnabled,
            bypassLanRouteEnabled = prefs(c).getBoolean("bypass_lan_route_enabled", false))
        save(c)
    }

    private fun encodeSubscriptions(): String {
        val json = JSONObject()
        subscriptionLinks.forEach { (url, links) -> json.put(url, org.json.JSONArray(links)) }
        return json.toString()
    }

    private fun decodeSubscriptions(raw: String?): LinkedHashMap<String, List<String>> = runCatching {
        val json = JSONObject(raw ?: "{}")
        LinkedHashMap<String, List<String>>().apply {
            json.keys().forEach { url ->
                val array = json.optJSONArray(url) ?: return@forEach
                val links = (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                if (links.isNotEmpty()) put(url, links)
            }
        }
    }.getOrDefault(linkedMapOf())

    private fun encodeSubscriptionNames(): String = JSONObject().apply {
        subscriptionNames.forEach { (url, name) -> put(url, name) }
    }.toString()

    private fun decodeSubscriptionNames(raw: String?): LinkedHashMap<String, String> = runCatching {
        val json = JSONObject(raw ?: "{}")
        LinkedHashMap<String, String>().apply {
            json.keys().forEach { url -> json.optString(url).trim().takeIf(String::isNotBlank)?.let { put(url, it) } }
        }
    }.getOrDefault(linkedMapOf())

    private fun save(c: Context) {
        val raws = (manualLinks + subscriptionLinks.values.flatten()).distinct()
        val servers = raws.mapNotNull(ConfigParser::parse)
        val selectedRaw = state.value.current?.raw
        val selected = selectedRaw?.let { raw -> servers.indexOfFirst { it.raw == raw } }
            ?.takeIf { it >= 0 } ?: state.value.selected.coerceIn(0, (servers.size - 1).coerceAtLeast(0))
        state.value = state.value.copy(servers = servers, selected = selected,
            subscriptions = subscriptionLinks.keys.toList(),
            subscriptionNames = LinkedHashMap(subscriptionNames),
            manualServers = manualLinks.mapNotNull(ConfigParser::parse),
            subscriptionServers = subscriptionLinks.mapValues { (_, links) -> links.mapNotNull(ConfigParser::parse) })
        prefs(c).edit()
            .putString("links", raws.joinToString("\u0001"))
            .putString("manual_links", manualLinks.joinToString("\u0001"))
            .putString("server_subscriptions", encodeSubscriptions())
            .putString("server_subscription_names", encodeSubscriptionNames())
            .putInt("sel", selected)
            .apply()
    }

    /** يضيف روابط من نص (سطر لكل رابط). يعيد عدد ما أُضيف. */
    fun import(c: Context, text: String): Int {
        val candidates = SubscriptionParser.extract(text) + text.lines().filter { it.trim().startsWith("edited://") }
        val added = candidates.distinct().filter { ConfigParser.parse(it) != null }
        val known = (manualLinks + subscriptionLinks.values.flatten()).toSet()
        val newLinks = added.filterNot { it in known }.distinct()
        manualLinks = manualLinks + newLinks
        save(c)
        return newLinks.size
    }

    suspend fun updateSubscription(c: Context, url: String, name: String? = null): Int {
        val normalizedUrl = url.trim()
        require(normalizedUrl.isNotBlank()) { "أدخل رابط الاشتراك" }
        val selectedRaw = state.value.current?.raw
        val wasSelectedFromThisSubscription = selectedRaw != null &&
            subscriptionLinks[normalizedUrl].orEmpty().contains(selectedRaw)
        val links = withContext(Dispatchers.IO) { SubscriptionParser.fetch(normalizedUrl) }
            .filter { ConfigParser.parse(it) != null }
            .distinct()
        require(links.isNotEmpty()) { "لم يتضمن الاشتراك أي سيرفر مدعوم" }
        // Keep a live selected node if the provider removes it during refresh.
        val refreshedLinks = if (state.value.connected && wasSelectedFromThisSubscription &&
            selectedRaw !in links) links + selectedRaw!! else links
        subscriptionLinks[normalizedUrl] = refreshedLinks
        name?.trim()?.takeIf(String::isNotBlank)?.let { subscriptionNames[normalizedUrl] = it }
        if (subscriptionNames[normalizedUrl].isNullOrBlank())
            subscriptionNames[normalizedUrl] = normalizedUrl.substringAfterLast('/').ifBlank { "اشتراك سيرفرات" }
        save(c)
        return links.size
    }

    fun renameSubscription(c: Context, url: String, name: String) {
        name.trim().takeIf(String::isNotBlank)?.let { subscriptionNames[url] = it }
        save(c)
    }

    fun removeSubscription(c: Context, url: String) {
        subscriptionLinks.remove(url)
        subscriptionNames.remove(url)
        save(c)
    }

    fun select(c: Context, i: Int) {
        if (i !in state.value.servers.indices) return
        state.value = state.value.copy(selected = i)
        save(c)
    }
    fun remove(c: Context, i: Int) {
        val raw = state.value.servers.getOrNull(i)?.raw ?: return
        manualLinks = manualLinks.filterNot { it == raw }
        subscriptionLinks.entries.forEach { entry -> entry.setValue(entry.value.filterNot { it == raw }) }
        subscriptionLinks.entries.removeAll { it.value.isEmpty() }
        state.value = state.value.copy(selected = 0)
        save(c)
    }

    fun editServer(c: Context, i: Int, name: String, host: String, port: Int, format: String): Boolean {
        if (host.isBlank() || port !in 1..65535 || format.isBlank()) return false
        val current = state.value.servers.getOrNull(i) ?: return false
        val updated = ConfigParser.edit(current, name, host, port, format) ?: return false
        replaceRaw(current.raw, updated.raw)
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
        replaceRaw(current.raw, updated.raw)
        state.value = state.value.copy(servers = state.value.servers.mapIndexed { index, server ->
            if (index == i) updated else server
        })
        save(c)
        return true
    }

    private fun replaceRaw(oldRaw: String, newRaw: String) {
        manualLinks = manualLinks.map { if (it == oldRaw) newRaw else it }
        subscriptionLinks.keys.toList().forEach { url ->
            subscriptionLinks[url] = subscriptionLinks[url].orEmpty().map { if (it == oldRaw) newRaw else it }
        }
    }

    fun clearAll(c: Context) {
        manualLinks = emptyList()
        subscriptionLinks.clear()
        subscriptionNames.clear()
        state.value = state.value.copy(servers = emptyList(), selected = 0, error = null,
            subscriptions = emptyList(), subscriptionNames = emptyMap())
        save(c)
    }

    fun saveExcludedApps(c: Context, packages: Set<String>) {
        val safePackages = packages.filter { it != c.packageName }.toSet()
        prefs(c).edit().putStringSet("excluded_apps", safePackages.toMutableSet()).apply()
        state.value = state.value.copy(excludedApps = safePackages)
    }

    fun setLocalDnsEnabled(c: Context, enabled: Boolean) {
        val restartRequired = state.value.connected || state.value.connecting || settingsRestartJob?.isActive == true
        prefs(c).edit().putBoolean("local_dns_enabled", enabled).apply()
        state.value = state.value.copy(localDnsEnabled = enabled)
        restartAfterSettingsChange(c, restartRequired)
    }

    fun setBypassLanRouteEnabled(c: Context, enabled: Boolean) {
        val restartRequired = state.value.connected || state.value.connecting || settingsRestartJob?.isActive == true
        prefs(c).edit().putBoolean("bypass_lan_route_enabled", enabled).apply()
        state.value = state.value.copy(bypassLanRouteEnabled = enabled)
        restartAfterSettingsChange(c, restartRequired)
    }

    private fun restartAfterSettingsChange(c: Context, restartRequired: Boolean) {
        settingsRestartJob?.cancel()
        if (restartRequired) {
            stop(c)
            settingsRestartJob = scope.launch {
                delay(800)
                start(c.applicationContext)
            }
        }
    }

    fun start(c: Context) {
        reconnectJob?.cancel()
        reconnectAttempts = 0
        reconnectEnabled = true
        startAttempt(c)
    }

    private fun startAttempt(c: Context) {
        appContext = c.applicationContext
        val s = state.value.current ?: run {
            log("فشل البدء: لا يوجد سيرفر محدد")
            state.value = state.value.copy(error = "أضف رابطاً أولاً")
            return
        }
        log("بدء الاتصال: ${s.protocol} — ${s.host}:${s.port}")
        val config = ConfigParser.buildConfig(s, useLocalDns = state.value.localDnsEnabled)
        c.getSharedPreferences("vpn_runtime", Context.MODE_PRIVATE).edit()
            .putString("active_config", config).apply()
        stopRequested = false
        state.value = state.value.copy(connected = false, connecting = true, error = null,
            publicIp = null, countryName = null, countryCode = null, ipLoading = true,
            totalUpKB = 0, totalDownKB = 0, upBps = 0, downBps = 0)
        val intent = Intent(c, SingBoxService::class.java).putExtra("config", config)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(intent)
        else c.startService(intent)
    }

    fun stop(c: Context) {
        appContext = c.applicationContext
        reconnectEnabled = false
        reconnectJob?.cancel()
        log("طلب فصل يدوي")
        stopRequested = true
        c.getSharedPreferences("vpn_runtime", Context.MODE_PRIVATE).edit().remove("active_config").apply()
        SingBoxService.stopNow()
        c.stopService(Intent(c, SingBoxService::class.java))
        onDisconnected()
    }

    fun onConnected() {
        reconnectAttempts = 0
        reconnectJob?.cancel()
        log("تم تشغيل النفق بنجاح")
        sessionStartedAt = System.currentTimeMillis()
        sessionServerName = state.value.current?.name ?: "—"
        sessionProtocol = state.value.current?.protocol ?: "—"
        state.value = state.value.copy(connected = true, connecting = false, ipLoading = true,
            publicIp = null, countryName = null, countryCode = null)
        statsJob?.cancel()
        statsJob = scope.launch {
            val uid = Process.myUid(); var tx = TrafficStats.getUidTxBytes(uid); var rx = TrafficStats.getUidRxBytes(uid)
            if (tx < 0) tx = TrafficStats.getTotalTxBytes()
            if (rx < 0) rx = TrafficStats.getTotalRxBytes()
            var sampledAt = android.os.SystemClock.elapsedRealtime()
            var totalTx = 0L; var totalRx = 0L
            launch {
                while (isActive) {
                    measurePing()
                    delay(4_000)
                }
            }
            while (isActive) {
                delay(500)
                var ntx = TrafficStats.getUidTxBytes(uid); var nrx = TrafficStats.getUidRxBytes(uid)
                if (ntx < 0) ntx = TrafficStats.getTotalTxBytes()
                if (nrx < 0) nrx = TrafficStats.getTotalRxBytes()
                val deltaTx = (ntx - tx).coerceAtLeast(0); val deltaRx = (nrx - rx).coerceAtLeast(0)
                val now = android.os.SystemClock.elapsedRealtime()
                val elapsedMs = (now - sampledAt).coerceAtLeast(1)
                val uploadRate = deltaTx * 1000 / elapsedMs
                val downloadRate = deltaRx * 1000 / elapsedMs
                totalTx += deltaTx; totalRx += deltaRx
                state.value = state.value.copy(upKB = uploadRate / 1024, downKB = downloadRate / 1024,
                    upBps = uploadRate, downBps = downloadRate,
                    totalUpKB = totalTx / 1024, totalDownKB = totalRx / 1024)
                tx = ntx; rx = nrx; sampledAt = now
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
            upBps = 0, downBps = 0,
            pingMs = -1, error = error, publicIp = null, countryName = null, countryCode = null, ipLoading = false)
        if (!error.isNullOrBlank() && reconnectEnabled && reconnectAttempts < 3) {
            val attempt = ++reconnectAttempts
            val waitSeconds = listOf(2L, 5L, 10L)[attempt - 1]
            val context = appContext ?: return
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                log("إعادة محاولة الاتصال $attempt/3 بعد ${waitSeconds} ثوانٍ")
                delay(waitSeconds * 1000)
                if (reconnectEnabled && !state.value.connected && !state.value.connecting) {
                    startAttempt(context.applicationContext)
                }
            }
        }
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
