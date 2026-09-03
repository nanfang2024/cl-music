package com.yue.tool.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class DownloadRecord(
    val name: String,
    val artist: String,
    val format: String, // 扩展名：mp3 / flac / m4a ...
    val size: Long,
    val time: Long,     // 下载完成的时间戳
    val uri: String     // content:// 或 file 绝对路径
)

/**
 * 下载历史：SharedPreferences + Gson 持久化，列表量小无需数据库
 */
object DownloadHistory {

    private const val PREFS = "download_history"
    private const val KEY = "records"
    private const val MAX = 200

    private val gson = Gson()

    fun list(context: Context): MutableList<DownloadRecord> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        return runCatching {
            val type = object : TypeToken<MutableList<DownloadRecord>>() {}.type
            gson.fromJson<MutableList<DownloadRecord>>(raw, type) ?: mutableListOf()
        }.getOrDefault(mutableListOf())
    }

    fun add(context: Context, record: DownloadRecord) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val records = list(context)
        // 同名同格式视为重复下载，刷新时间移到最前
        records.removeAll { it.name == record.name && it.artist == record.artist && it.format == record.format }
        records.add(0, record)
        while (records.size > MAX) records.removeAt(records.size - 1)
        prefs.edit().putString(KEY, gson.toJson(records)).apply()
    }

    fun remove(context: Context, record: DownloadRecord) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val records = list(context)
        records.removeAll { it.time == record.time && it.name == record.name }
        prefs.edit().putString(KEY, gson.toJson(records)).apply()
    }
}
