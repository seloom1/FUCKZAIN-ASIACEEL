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
            r.startsWith("vless://") -> uri(r, "vless")
            r.startsWith("trojan://") -> uri(r, "trojan")
            r.startsWith("vmess://") -> vmess(r)
            r.contains("[Interface]") -> wireguard(r)
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

    private fun wireguard(raw: String): Server {
        val kv = mutableMapOf<String, String>()
        raw.lines().forEach { l -> if ("=" in l && !l.startsWith("#"))
            kv[l.substringBefore("=").trim().lowercase()] = l.substringAfter("=").trim() }
        val ep = kv.getValue("endpoint"); val host = ep.substringBeforeLast(":").trim('[', ']')
        val port = ep.substringAfterLast(":").toInt()
        val o = JSONObject().put("type", "wireguard").put("tag", "proxy")
            .put("server", host).put("server_port", port)
            .put("local_address", JSONArray(kv.getValue("address").split(",").map { it.trim() }))
            .put("private_key", kv.getValue("privatekey"))
            .put("peer_public_key", kv.getValue("publickey"))
            .put("mtu", kv["mtu"]?.toIntOrNull() ?: 1420)
        kv["presharedkey"]?.let { o.put("pre_shared_key", it) }
        return Server(raw, "WireGuard $host", "WIREGUARD", host, port, o)
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
