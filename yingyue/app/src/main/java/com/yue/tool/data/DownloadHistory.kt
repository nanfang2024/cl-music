package com.yue.tool.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class DownloadRecord(
    val name: String,
    val artist: String,
    val source: String,      // netease | joox | kuwo
    val format: String,      // flac / mp3 / m4a / ogg
    val quality: String,     // FLAC无损 / 320k / 128k
    val time: Long,          // 下载时间戳
    val uri: String,         // content:// 或 file://
    val size: Long = 0,      // 文件大小（字节）
    val coverUrl: String? = null // 封面图 URL
)

object DownloadHistory {
    private const val PREFS = "download_history"
    private const val KEY = "records"
    private const val MAX = 200
    private val gson = Gson()

    fun list(context: Context): MutableList<DownloadRecord> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = sp.getString(KEY, null) ?: return mutableListOf()
        return try {
            gson.fromJson(json, object : TypeToken<MutableList<DownloadRecord>>() {}.type)
                ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun add(context: Context, record: DownloadRecord) {
        val records = list(context)
        records.removeAll {
            it.name == record.name && it.artist == record.artist && it.format == record.format
        }
        records.add(0, record)
        while (records.size > MAX) records.removeAt(records.size - 1)
        save(context, records)
    }

    fun remove(context: Context, record: DownloadRecord) {
        val records = list(context)
        records.removeAll {
            it.name == record.name && it.artist == record.artist &&
                    it.format == record.format && it.time == record.time
        }
        save(context, records)
    }

    private fun save(context: Context, records: List<DownloadRecord>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, gson.toJson(records)).apply()
    }
}
