package com.seloom.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UsageSession(
    val startedAt: Long,
    val endedAt: Long,
    val serverName: String,
    val protocol: String,
    val uploadedKB: Long,
    val downloadedKB: Long
)

object UsageHistory {
    private const val PREFS = "usage_history"
    private const val KEY = "sessions"
    private const val MAX_SESSIONS = 100

    @Synchronized
    fun add(context: Context, session: UsageSession) {
        val sessions = (read(context) + session).takeLast(MAX_SESSIONS)
        val json = JSONArray()
        sessions.forEach {
            json.put(JSONObject()
                .put("startedAt", it.startedAt)
                .put("endedAt", it.endedAt)
                .put("serverName", it.serverName.take(100))
                .put("protocol", it.protocol.take(30))
                .put("uploadedKB", it.uploadedKB.coerceAtLeast(0))
                .put("downloadedKB", it.downloadedKB.coerceAtLeast(0)))
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.toString()).apply()
    }

    @Synchronized
    fun read(context: Context): List<UsageSession> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { index ->
                json.optJSONObject(index)?.let {
                    UsageSession(
                        it.optLong("startedAt"), it.optLong("endedAt"),
                        it.optString("serverName", "—"), it.optString("protocol", "—"),
                        it.optLong("uploadedKB").coerceAtLeast(0),
                        it.optLong("downloadedKB").coerceAtLeast(0)
                    )
                }
            }.reversed()
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    fun time(at: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at))
    fun data(kb: Long): String = when {
        kb >= 1024 * 1024 -> "%.2f GB".format(kb / (1024f * 1024f))
        kb >= 1024 -> "%.2f MB".format(kb / 1024f)
        else -> "$kb KB"
    }
}
