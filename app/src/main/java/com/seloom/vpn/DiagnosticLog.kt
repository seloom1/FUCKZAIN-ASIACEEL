package com.seloom.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** سجل محلي محدود لأحداث الاتصال، بدون مفاتيح أو محتوى إعدادات حساسة. */
data class DiagnosticEntry(val at: Long, val message: String)

object DiagnosticLog {
    private const val PREFS = "diagnostic_log"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 100

    @Synchronized
    fun record(context: Context, message: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = read(context).toMutableList()
        entries.add(0, DiagnosticEntry(System.currentTimeMillis(), message.take(240)))
        val json = JSONArray()
        entries.take(MAX_ENTRIES).forEach {
            json.put(JSONObject().put("at", it.at).put("message", it.message))
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    @Synchronized
    fun read(context: Context): List<DiagnosticEntry> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { index ->
                json.optJSONObject(index)?.let {
                    DiagnosticEntry(it.optLong("at"), it.optString("message"))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    fun formatTime(at: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at))
}
