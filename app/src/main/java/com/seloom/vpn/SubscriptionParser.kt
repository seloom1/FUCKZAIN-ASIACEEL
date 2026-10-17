package com.seloom.vpn

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Fetches HTTPS subscriptions and extracts supported links from plain or Base64 text. */
object SubscriptionParser {
    private const val MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024
    private val linkPattern = Regex("(?i)(?:vless|vmess|trojan|wireguard)://[^\\s<>\\\"']+")

    fun fetch(urlText: String): List<String> {
        val url = urlText.trim()
        val uri = URI(url)
        require(uri.scheme?.equals("https", ignoreCase = true) == true && !uri.host.isNullOrBlank()) {
            "استخدم رابط اشتراك يبدأ بـ https://"
        }
        require(uri.userInfo == null) { "لا تضع بيانات دخول داخل رابط الاشتراك" }

        val connection = (URL(url).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "text/plain, */*")
            connection.setRequestProperty("User-Agent", "FUCKZAIN-ASIACEEL Android")
            val status = connection.responseCode
            require(connection.url.protocol.equals("https", ignoreCase = true)) {
                "رابط الاشتراك أعاد توجيهاً إلى اتصال غير مشفر"
            }
            require(status in 200..299) { "تعذّر جلب الاشتراك (HTTP $status)" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_SUBSCRIPTION_BYTES) { "حجم ملف الاشتراك أكبر من المسموح" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val text = bytes.toString(Charsets.UTF_8)
            val extracted = extract(text)
            require(extracted.isNotEmpty()) { "لم يتم العثور على روابط VLESS أو VMess أو Trojan أو WireGuard صالحة" }
            extracted
        } finally {
            connection.disconnect()
        }
    }

    /** Extracts multiple links even when they share a Markdown line or are Base64 encoded. */
    fun extract(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.contains("[Interface]", ignoreCase = true) &&
            trimmed.contains("PrivateKey", ignoreCase = true)) return listOf(trimmed)

        val direct = findLinks(trimmed)
        if (direct.isNotEmpty()) return direct

        val compact = trimmed.filterNot(Char::isWhitespace)
        val decoded = runCatching {
            String(Base64.decode(compact, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()?.trim().orEmpty()
        if (decoded.isNotEmpty() && decoded != trimmed) {
            if (decoded.contains("[Interface]", ignoreCase = true) &&
                decoded.contains("PrivateKey", ignoreCase = true)) return listOf(decoded)
            return findLinks(decoded)
        }
        return emptyList()
    }

    private fun findLinks(text: String): List<String> = linkPattern.findAll(text)
        .map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}
