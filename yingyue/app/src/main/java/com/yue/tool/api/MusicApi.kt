package com.yue.tool.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Track(
    val id: String? = null,
    val name: String = "",
    val artist: List<String> = emptyList(),
    val album: String? = null,
    val pic_id: String? = null,
    val url_id: String? = null,
    val lyric_id: String? = null,
    val source: String = ""
) {
    val artistLine: String get() = if (artist.isEmpty()) "未知歌手" else artist.joinToString(" / ")
}

data class UrlResult(
    val url: String? = null,
    val br: Int? = null,
    val size: Long? = null
)

data class PicResult(val url: String? = null)

object MusicApi {

    private const val BASE = "https://music-api.gdstudio.xyz/api.php"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private fun get(url: String): String = with(Request.Builder().url(url).build()) {
        client.newCall(this).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string() ?: throw IOException("响应为空")
        }
    }

    suspend fun search(source: String, name: String, count: Int = 30, page: Int = 1): List<Track> =
        withContext(Dispatchers.IO) {
            val url = "$BASE?types=search&source=$source&name=${enc(name)}&count=$count&pages=$page"
            val type = object : TypeToken<List<Track>>() {}.type
            gson.fromJson<List<Track>>(get(url), type) ?: emptyList()
        }

    suspend fun fetchUrl(source: String, id: String, br: Int): UrlResult =
        withContext(Dispatchers.IO) {
            val url = "$BASE?types=url&source=$source&id=${enc(id)}&br=$br"
            gson.fromJson(get(url), UrlResult::class.java)
        }

    suspend fun fetchPic(source: String, picId: String, size: Int = 300): PicResult =
        withContext(Dispatchers.IO) {
            val url = "$BASE?types=pic&source=$source&id=${enc(picId)}&size=$size"
            runCatching { gson.fromJson(get(url), PicResult::class.java) }.getOrDefault(PicResult())
        }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
