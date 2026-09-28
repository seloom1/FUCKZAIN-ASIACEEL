package com.seloom.vpn

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

data class Server(val raw: String, val name: String, val protocol: String,
                  val host: String, val port: Int, val outbound: JSONObject)

/** يحوّل روابط vless/vmess/trojan وملفات WireGuard إلى Outbound لـ sing-box */
object ConfigParser {
    const val IP_CHECK_PROXY_PORT = 20872

    fun parse(raw: String): Server? = try {
        val r = raw.trim()
        when {
            r.startsWith("wireguard://") -> wireguardUri(r)
            r.startsWith("edited://") -> {
                val j = JSONObject(String(Base64.decode(r.removePrefix("edited://"), Base64.DEFAULT)))
                Server(r, j.getString("name"), j.getString("protocol"), j.getString("host"),
                    j.getInt("port"), JSONObject(j.getString("outbound")))
            }
            r.startsWith("vless://") -> uri(r, "vless")
            r.startsWith("trojan://") -> uri(r, "trojan")
            r.startsWith("vmess://") -> vmess(r)
            r.contains("[Interface]") || r.contains("PrivateKey") -> wireguard(r)
            else -> null
        }
    } catch (e: Exception) { null }

    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    private fun tls(q: Map<String, String>, sec: String, host: String, sniKey: String = "sni"): JSONObject? {
        if (sec != "tls" && sec != "reality") return null
        val t = JSONObject().put("enabled", true)
            .put("server_name", q[sniKey]?.ifEmpty { null } ?: host)
        q["alpn"]?.let { t.put("alpn", JSONArray(it.split(","))) }
        q["allowInsecure"]?.let { if (it == "1") t.put("insecure", true) }
        val fp = q["fp"]
        if (!fp.isNullOrEmpty() || sec == "reality")
            t.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp ?: "chrome"))
        if (sec == "reality")
            t.put("reality", JSONObject().put("enabled", true)
                .put("public_key", q["pbk"] ?: "").put("short_id", q["sid"] ?: ""))
        return t
    }

    private fun transport(type: String, path: String?, host: String?): JSONObject? = when (type) {
        "ws" -> JSONObject().put("type", "ws").put("path", path ?: "/")
            .apply { if (!host.isNullOrEmpty()) put("headers", JSONObject().put("Host", host)) }
        "grpc" -> JSONObject().put("type", "grpc").put("service_name", path ?: "")
        "httpupgrade" -> JSONObject().put("type", "httpupgrade").put("path", path ?: "/")
            .apply { if (!host.isNullOrEmpty()) put("host", host) }
        else -> null
    }

    private fun uri(raw: String, proto: String): Server {
        val u = URI(raw)
        val q = (u.rawQuery ?: "").split("&").filter { it.contains("=") }
            .associate { it.substringBefore("=") to dec(it.substringAfter("=")) }
        val host = u.host; val port = u.port
        val o = JSONObject().put("type", proto).put("tag", "proxy")
            .put("server", host).put("server_port", port)
        if (proto == "vless") {
            o.put("uuid", u.userInfo)
            q["flow"]?.takeIf { it.isNotEmpty() }?.let { o.put("flow", it) }
        } else o.put("password", dec(u.userInfo))
        tls(q, q["security"] ?: if (proto == "trojan") "tls" else "none", host)?.let { o.put("tls", it) }
        transport(q["type"] ?: "tcp", q["path"] ?: q["serviceName"], q["host"])?.let { o.put("transport", it) }
        val name = u.rawFragment?.let { dec(it) } ?: "$host:$port"
        return Server(raw, name, proto.uppercase(), host, port, o)
    }

    private fun vmess(raw: String): Server {
        val j = JSONObject(String(Base64.decode(raw.removePrefix("vmess://"), Base64.DEFAULT)))
        val host = j.getString("add"); val port = j.getString("port").toInt()
        val o = JSONObject().put("type", "vmess").put("tag", "proxy")
            .put("server", host).put("server_port", port).put("uuid", j.getString("id"))
            .put("security", j.optString("scy", "auto").ifEmpty { "auto" })
            .put("alter_id", j.optString("aid", "0").toIntOrNull() ?: 0)
        val q = mapOf("sni" to j.optString("sni"), "alpn" to j.optString("alpn"), "fp" to j.optString("fp"))
        tls(q, if (j.optString("tls") == "tls") "tls" else "none", host)?.let { o.put("tls", it) }
        transport(j.optString("net", "tcp"), j.optString("path"), j.optString("host"))?.let { o.put("transport", it) }
        return Server(raw, j.optString("ps").ifEmpty { "$host:$port" }, "VMESS", host, port, o)
    }

    private fun wireguardUri(raw: String): Server {
        val u = URI(raw)
        val q = (u.rawQuery ?: "").split("&").filter { it.contains("=") }
            .associate { dec(it.substringBefore("=")).lowercase() to dec(it.substringAfter("=")) }
        val host = u.host ?: error("WireGuard endpoint host is missing")
        val port = u.port.takeIf { it in 1..65535 } ?: 2408
        // rawUserInfo is still percent-encoded. Decoding it exactly once avoids
        // turning a literal '+' in a base64 key into a space (URLDecoder treats
        // '+' as a form-space character). This was not an issue for the bundled
        // WARP key, but breaks otherwise valid WireGuard links.
        val privateKey = u.rawUserInfo?.let { dec(it) } ?: q["privatekey"] ?: q["private_key"] ?: ""
        val publicKey = q["publickey"] ?: q["public_key"] ?: ""
        require(privateKey.isNotBlank()) { "WireGuard private key is missing" }
        require(publicKey.isNotBlank()) { "WireGuard peer public key is missing" }
        // Match the working native WireGuard client: a valid interface address
        // and a mobile-safe MTU are required defaults when the URI omits them.
        val address = q["address"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() } ?: listOf("172.16.0.2/32")
        val o = JSONObject().put("type", "wireguard").put("tag", "proxy")
            .put("server", host).put("server_port", port)
            .put("local_address", JSONArray(address))
            .put("private_key", privateKey)
            .put("peer_public_key", publicKey)
            .put("mtu", q["mtu"]?.toIntOrNull()?.takeIf { it >= 1280 } ?: 1280)
        q["presharedkey"]?.let { o.put("pre_shared_key", it) }
        val name = u.rawFragment?.let { dec(it) } ?: "WireGuard $host"
        return Server(raw, name, "WIREGUARD", host, port, o)
    }

    private fun wireguard(raw: String): Server {
        val kv = mutableMapOf<String, String>()
        raw.lines().forEach { l -> if ("=" in l && !l.startsWith("#"))
            kv[l.substringBefore("=").trim().lowercase()] = l.substringAfter("=").trim() }
        val ep = kv.getValue("endpoint"); val host = ep.substringBeforeLast(":").trim('[', ']')
        val port = ep.substringAfterLast(":").toInt()
        val address = kv["address"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() } ?: listOf("172.16.0.2/32")
        val o = JSONObject().put("type", "wireguard").put("tag", "proxy")
            .put("server", host).put("server_port", port)
            .put("local_address", JSONArray(address))
            .put("private_key", kv.getValue("privatekey"))
            .put("peer_public_key", kv.getValue("publickey"))
            .put("mtu", kv["mtu"]?.toIntOrNull()?.takeIf { it >= 1280 } ?: 1280)
        kv["presharedkey"]?.let { o.put("pre_shared_key", it) }
        return Server(raw, "WireGuard $host", "WIREGUARD", host, port, o)
    }

    fun edit(server: Server, name: String, host: String, port: Int, format: String): Server? = try {
        val parts = format.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val protocol = parts.firstOrNull()?.takeIf { it in setOf("vless", "vmess", "trojan", "wireguard") }
            ?: server.protocol.lowercase()
        val transportType = parts.firstOrNull { it in setOf("ws", "grpc", "httpupgrade", "tcp") }
        val tlsEnabled = parts.any { it == "tls" || it == "reality" }
        val updated = JSONObject(server.outbound.toString())
            .put("type", protocol).put("server", host).put("server_port", port)
        if (transportType != null && transportType != "tcp") {
            val old = server.outbound.optJSONObject("transport")
            val transport = JSONObject().put("type", transportType)
            if (transportType == "ws" || transportType == "httpupgrade")
                transport.put("path", old?.optString("path", "/") ?: "/")
            if (transportType == "grpc")
                transport.put("service_name", old?.optString("service_name", "") ?: "")
            updated.put("transport", transport)
        } else updated.remove("transport")
        if (tlsEnabled) {
            val oldTls = server.outbound.optJSONObject("tls")
            updated.put("tls", (oldTls ?: JSONObject()).put("enabled", true).put("server_name", host))
        } else updated.remove("tls")
        val finalName = name.trim().ifEmpty { "$host:$port" }
        val rawJson = JSONObject().put("name", finalName).put("protocol", protocol.uppercase())
            .put("host", host).put("port", port).put("outbound", updated.toString())
        val encoded = Base64.encodeToString(rawJson.toString().toByteArray(), Base64.NO_WRAP)
        Server("edited://$encoded", finalName, protocol.uppercase(), host, port, updated)
    } catch (_: Exception) { null }

    /** يبني نسخة VLESS جديدة مع كل حقول الاتصال التي يدعمها الرابط/Outbound. */
    fun editVless(server: Server, name: String, host: String, port: Int, uuid: String, flow: String,
                  security: String, sni: String, alpn: String, insecure: Boolean, fingerprint: String,
                  realityPublicKey: String, realityShortId: String, transportType: String,
                  pathOrService: String, transportHost: String): Server? {
        return try {
        if (host.isBlank() || uuid.isBlank() || port !in 1..65535) return null
        val o = JSONObject(server.outbound.toString())
            .put("type", "vless").put("server", host).put("server_port", port).put("uuid", uuid)
        if (flow.isBlank()) o.remove("flow") else o.put("flow", flow)
        val transport = transportType.trim().lowercase()
        if (transport !in setOf("tcp", "ws", "grpc", "httpupgrade")) return null
        if (transport == "tcp") o.remove("transport") else {
            val t = JSONObject().put("type", transport)
            when (transport) {
                "ws", "httpupgrade" -> {
                    t.put("path", pathOrService.ifBlank { "/" })
                    if (transportHost.isNotBlank()) {
                        if (transport == "ws") t.put("headers", JSONObject().put("Host", transportHost))
                        else t.put("host", transportHost)
                    }
                }
                "grpc" -> t.put("service_name", pathOrService)
            }
            o.put("transport", t)
        }
        val sec = security.trim().lowercase()
        if (sec !in setOf("none", "tls", "reality")) return null
        if (sec == "none") o.remove("tls") else {
            val tls = JSONObject().put("enabled", true).put("server_name", sni.ifBlank { host })
            if (alpn.isNotBlank()) tls.put("alpn", JSONArray(alpn.split(",").map { it.trim() }.filter { it.isNotBlank() }))
            if (insecure) tls.put("insecure", true)
            if (fingerprint.isNotBlank()) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
            if (sec == "reality") tls.put("reality", JSONObject().put("enabled", true)
                .put("public_key", realityPublicKey).put("short_id", realityShortId))
            o.put("tls", tls)
        }
        val finalName = name.trim().ifEmpty { "$host:$port" }
        val rawJson = JSONObject().put("name", finalName).put("protocol", "VLESS")
            .put("host", host).put("port", port).put("outbound", o.toString())
        val encoded = Base64.encodeToString(rawJson.toString().toByteArray(), Base64.NO_WRAP)
        Server("edited://$encoded", finalName, "VLESS", host, port, o)
        } catch (_: Exception) { null }
    }

    /** الإعداد الكامل لـ sing-box 1.10.x */
    fun buildConfig(s: Server): String {
        val dns = JSONObject()
            .put("servers", JSONArray()
                // Same behavior as NetMod: resolve through the external network
                // resolver instead of creating a DNS-over-proxy loop.
                .put(JSONObject().put("tag", "local").put("address", "local").put("detour", "direct"))
                .put(JSONObject().put("tag", "remote").put("address", "tls://1.1.1.1").put("detour", "proxy")))
            .put("strategy", "ipv4_only")
            .put("final", "local")
        val tun = JSONObject()
            .put("type", "tun").put("tag", "tun-in")
            .put("address", JSONArray().put("172.19.0.1/30"))
            .put("mtu", 1400)
            // Android VpnService.Builder installs the routes; sing-box must not
            // start its Linux netlink route monitor on a regular Android app.
            .put("auto_route", false).put("stack", "gvisor").put("sniff", true)
        val ipCheckProxy = JSONObject()
            .put("type", "http").put("tag", "ip-check-in")
            .put("listen", "127.0.0.1").put("listen_port", IP_CHECK_PROXY_PORT)
        val route = JSONObject()
            // Keep auto detection enabled so Platform.autoDetectInterfaceControl
            // protects sing-box sockets from looping back through the VPN.
            .put("auto_detect_interface", true).put("final", "proxy")
            .put("rules", JSONArray()
                .put(JSONObject().put("inbound", JSONArray().put("ip-check-in")).put("outbound", "proxy"))
                .put(JSONObject().put("network", "udp").put("port", 443).put("outbound", "block"))
                .put(JSONObject().put("protocol", "dns").put("outbound", "dns-out")))
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun).put(ipCheckProxy))
            .put("outbounds", JSONArray().put(s.outbound)
                .put(JSONObject().put("type", "direct").put("tag", "direct"))
                .put(JSONObject().put("type", "dns").put("tag", "dns-out"))
                .put(JSONObject().put("type", "block").put("tag", "block")))
            .put("route", route)
            .toString()
    }
}
