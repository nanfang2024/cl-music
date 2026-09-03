package com.yue.tool.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Track(
    val id: String,
    val source: String,      // netease | joox | kuwo
    val name: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,
    val keyword: String,
    val index: Int           // 搜索结果中的序号，绿鹅/库窝详情接口按序号取歌
)

data class ResolvedUrl(
    val url: String,
    val ext: String,         // flac / mp3 / m4a / ogg
    val mime: String,
    val qualityLabel: String // FLAC无损 / 320k / 128k
)

/**
 * 三音源聚合：
 * - 芸朵（netease）：GD Studio 音乐台 API
 * - 绿鹅（joox）：apicx.asia JOOX 接口（参考 musicsquare 项目）
 * - 库窝（kuwo）：oiapi.net 酷我接口（参考 musicsquare 项目）
 */
object MusicApi {

    private const val GD_API = "https://music-api.gdstudio.xyz/api.php"
    private const val JOOX_API = "https://apicx.asia/api/joox_music"
    private const val JOOX_TOKEN = "f84ao9lMF_q7husBWRfgUw"
    private const val KUWO_API = "https://oiapi.net/api/Kuwo"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $url")
            return resp.body?.string() ?: ""
        }
    }

    private fun parse(s: String): JsonObject? = try {
        JsonParser.parseString(s).asJsonObject
    } catch (e: Exception) {
        null
    }

    // ==================== 搜索 ====================

    fun search(keyword: String, sources: List<String>): List<Track> {
        val out = mutableListOf<Track>()
        for (src in sources) {
            try {
                when (src) {
                    "netease" -> out += searchNetease(keyword)
                    "joox" -> out += searchJoox(keyword)
                    "kuwo" -> out += searchKuwo(keyword)
                }
            } catch (_: Exception) {
                // 单个音源失败不影响其它音源
            }
        }
        return out
    }

    /** 芸朵（网易云）：GD Studio */
    private fun searchNetease(keyword: String): List<Track> {
        val url = "$GD_API?types=search&source=netease&name=${enc(keyword)}&count=30&pages=1"
        val arr = try {
            JsonParser.parseString(get(url)).asJsonArray
        } catch (e: Exception) {
            return emptyList()
        }
        val out = mutableListOf<Track>()
        arr.forEach { el ->
            try {
                val o = el.asJsonObject
                val artistEl = o.get("artist")?.takeIf { !it.isJsonNull }
                val artist = when {
                    artistEl == null -> ""
                    artistEl.isJsonArray ->
                        artistEl.asJsonArray.joinToString("/") { el -> el.asString }
                    else -> artistEl.asString
                }
                out += Track(
                    id = o["id"].asString,
                    source = "netease",
                    name = o["name"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    artist = artist,
                    album = o["album"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    coverUrl = null,
                    keyword = keyword,
                    index = out.size + 1
                )
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** 绿鹅（JOOX）：apicx.asia */
    private fun searchJoox(keyword: String): List<Track> {
        val url = "$JOOX_API?msg=${enc(keyword)}&token=$JOOX_TOKEN&br=4"
        val root = parse(get(url)) ?: return emptyList()
        if (root.get("code")?.asInt != 200) return emptyList()
        val songs = root.getAsJsonObject("data")?.getAsJsonArray("songs") ?: return emptyList()
        val out = mutableListOf<Track>()
        songs.forEach { el ->
            try {
                val o = el.asJsonObject
                val songMid = o.get("songmid")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val songId = o.get("歌曲ID")?.takeIf { !it.isJsonNull }?.asString ?: songMid
                out += Track(
                    id = songMid.ifEmpty { songId },
                    source = "joox",
                    name = o["歌曲名称"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    artist = o["歌手"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    album = o["专辑"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    coverUrl = null,
                    keyword = keyword,
                    index = o.get("序号")?.takeIf { !it.isJsonNull }?.asInt ?: (out.size + 1)
                )
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** 库窝（酷我）：oiapi.net */
    private fun searchKuwo(keyword: String): List<Track> {
        val url = "$KUWO_API?msg=${enc(keyword)}&page=1&limit=30"
        val root = parse(get(url)) ?: return emptyList()
        val data = root.getAsJsonArray("data") ?: return emptyList()
        val out = mutableListOf<Track>()
        data.forEach { el ->
            try {
                val o = el.asJsonObject
                val rid = o["rid"]?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                out += Track(
                    id = rid,
                    source = "kuwo",
                    name = o["song"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    artist = o["singer"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    album = o["album"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    coverUrl = o["picture"]?.takeIf { !it.isJsonNull }?.asString,
                    keyword = keyword,
                    index = out.size + 1
                )
            } catch (_: Exception) {
            }
        }
        return out
    }

    // ==================== 解析播放链接 ====================

    /**
     * @param quality 音质档位：128k / 320k / 740k / 999k
     */
    fun resolveUrl(track: Track, quality: String): ResolvedUrl {
        return when (track.source) {
            "joox" -> resolveJoox(track, quality)
            "kuwo" -> resolveKuwo(track, quality)
            else -> resolveNetease(track, quality)
        }
    }

    /** 芸朵：types=url，br=128/320/740/999 */
    private fun resolveNetease(track: Track, quality: String): ResolvedUrl {
        val br = when (quality) {
            "128k" -> "128"
            "320k" -> "320"
            "740k" -> "740"
            else -> "999"
        }
        val url = "$GD_API?types=url&source=netease&id=${enc(track.id)}&br=$br"
        val root = parse(get(url))
            ?: throw RuntimeException("芸朵音源解析失败")
        val playUrl = root.get("url")?.takeIf { !it.isJsonNull }?.asString
            ?: throw RuntimeException("芸朵音源未返回链接")
        val fmt = formatFromUrl(playUrl)
        val actualBr = root.get("br")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        val label = if (fmt.ext == "flac") "FLAC无损" else "${actualBr}k"
        return ResolvedUrl(playUrl, fmt.ext, fmt.mime, label)
    }

    /** 绿鹅：详情接口返回多档播放链接，按音质档位择优探测 */
    private fun resolveJoox(track: Track, quality: String): ResolvedUrl {
        val url = "$JOOX_API?msg=${enc(track.keyword)}&n=${track.index}&token=$JOOX_TOKEN&br=4"
        val root = parse(get(url))
            ?: throw RuntimeException("绿鹅音源解析失败")
        if (root.get("code")?.asInt != 200) throw RuntimeException("绿鹅音源解析失败")
        val links = root.getAsJsonObject("data")?.getAsJsonObject("播放链接")
            ?: throw RuntimeException("绿鹅音源未返回播放链接")

        val tiers = when (quality) {
            "999k" -> listOf("母带无损", "Hi-Res无损", "无损FLAC", "Atmos全景声")
            "740k" -> listOf("Hi-Res无损", "无损FLAC", "母带无损", "Atmos全景声")
            "320k" -> listOf("OGG 320", "MP3 320", "AAC 192", "OGG 192")
            else -> listOf("MP3 128", "OGG 192", "AAC 96", "AAC 48", "MP3 320")
        }
        for (name in tiers) {
            val u = links.get(name)?.takeIf { !it.isJsonNull }?.asString ?: continue
            if (!probeUrl(u)) continue
            val fmt = formatFromUrl(u)
            val label = if (fmt.ext == "flac") {
                if (name.contains("母带")) "母带无损" else "FLAC无损"
            } else {
                name.substringAfterLast(" ")
            }
            return ResolvedUrl(u, fmt.ext, fmt.mime, label)
        }
        throw RuntimeException("绿鹅音源所有音质链接均不可用")
    }

    /** 库窝：br=7(128k) / 5(320k) / 1(flac) */
    private fun resolveKuwo(track: Track, quality: String): ResolvedUrl {
        val br = when (quality) {
            "128k" -> "7"
            "320k" -> "5"
            "740k" -> "1"
            else -> "1"
        }
        val url = "$KUWO_API?msg=${enc(track.keyword)}&n=${track.index}&br=$br"
        val root = parse(get(url))
            ?: throw RuntimeException("库窝音源解析失败")
        val data = root.getAsJsonObject("data")
            ?: throw RuntimeException("库窝音源未返回数据")
        val playUrl = data.get("url")?.takeIf { !it.isJsonNull }?.asString
            ?: throw RuntimeException("库窝音源未返回链接")
        val format = data.get("format")?.takeIf { !it.isJsonNull }?.asString ?: ""
        val bitrate = data.get("bitrate")?.takeIf { !it.isJsonNull }?.asString ?: ""
        val fmt = if (format.isNotEmpty()) {
            when (format.lowercase()) {
                "flac" -> AudioFormat("flac", "audio/flac")
                "mp3" -> AudioFormat("mp3", "audio/mpeg")
                "aac", "m4a" -> AudioFormat("m4a", "audio/mp4")
                "ogg" -> AudioFormat("ogg", "audio/ogg")
                else -> formatFromUrl(playUrl)
            }
        } else {
            formatFromUrl(playUrl)
        }
        val label = if (fmt.ext == "flac") "FLAC无损" else "${bitrate}k"
        return ResolvedUrl(playUrl, fmt.ext, fmt.mime, label)
    }

    // ==================== 工具 ====================

    data class AudioFormat(val ext: String, val mime: String)

    fun formatFromUrl(url: String): AudioFormat = when {
        url.contains(".flac", ignoreCase = true) -> AudioFormat("flac", "audio/flac")
        url.contains(".m4a", ignoreCase = true) -> AudioFormat("m4a", "audio/mp4")
        url.contains(".ogg", ignoreCase = true) -> AudioFormat("ogg", "audio/ogg")
        url.contains(".wav", ignoreCase = true) -> AudioFormat("wav", "audio/wav")
        else -> AudioFormat("mp3", "audio/mpeg")
    }

    /** 探测 CDN 链接可用性（部分 CDN 不支持 HEAD，回退 Range GET） */
    private fun probeUrl(u: String): Boolean {
        return try {
            client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(u).head().build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            try {
                client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build().newCall(
                    Request.Builder().url(u).header("Range", "bytes=0-0").build()
                ).execute().use { it.code in 200..399 || it.code == 206 }
            } catch (e2: Exception) {
                false
            }
        }
    }
}
