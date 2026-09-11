package com.yue.tool.api

import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Track(
    val id: String,
    val source: String,      // netease | joox | kuwo
    val name: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,
    val picId: String? = null, // 芸朵的 pic_id，用于延迟获取封面
    val keyword: String,
    val index: Int,          // 在该音源搜索结果中的原始序号（1-based），用于详情接口按序取歌
    val searchPage: Int = 1  // 该结果来自第几页
)

data class ResolvedUrl(
    val url: String,
    val ext: String,         // flac / mp3 / m4a / ogg
    val mime: String,
    val qualityLabel: String // FLAC无损 / 320k / 128k
)

/**
 * v1.5.0 三音源聚合（参考 musicdl 项目重构）：
 * - 芸朵（netease）：GD Studio 音乐台 API
 * - 绿鹅（joox）：apicx.asia JOOX 接口
 * - 库窝（kuwo）：酷我官方 mobi.s DES 加密接口 + 多级兜底链
 *
 * 关键改进（相对 v1.4.0）：
 * 1. 酷我搜索改用官方 www.kuwo.cn/search/searchMusicBykeyWord（替代已失效的 oiapi.net）
 * 2. 酷我解析改用官方 mobi.kuwo.cn/mobi.s DES 加密（KuwoDes.kt），含 nxinxz → xcloudv 兜底链
 * 3. 酷我歌词用官方 newlyric.kuwo.cn XOR+zlib 接口
 * 4. 移除 probeUrl HEAD 探测（审计 P0-1：部分 CDN 不支持 HEAD 导致误判）
 * 5. 错误信息完整透传，不吞原始异常
 */
object MusicApi {

    private const val GD_API = "https://music-api.gdstudio.xyz/api.php"
    private const val JOOX_API = "https://apicx.asia/api/joox_music"
    private const val JOOX_TOKEN = "f84ao9lMF_q7husBWRfgUw"

    // 酷我官方接口
    private const val KUWO_SEARCH_URL = "https://www.kuwo.cn/search/searchMusicBykeyWord"
    private const val KUWO_MOBI_URL = "https://mobi.kuwo.cn/mobi.s"
    private const val KUWO_LYRIC_URL = "https://newlyric.kuwo.cn/newlyric.lrc"
    // 酷我兜底解析接口
    private const val KUWO_FALLBACK_NXINXZ = "http://music.nxinxz.com/kw.php"
    private const val KUWO_FALLBACK_XCLOUDV = "https://music.xcloudv.top/php/kuwo_backup_source.php"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    private fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    private fun parseJson(s: String) = try {
        JsonParser.parseString(s).asJsonObject
    } catch (e: Exception) {
        null
    }

    // ==================== 搜索 ====================

    /**
     * @param keyword 搜索关键词
     * @param sources 音源列表
     * @param page 页码（1-based），每页 30 条
     * @return 去重后的结果列表
     */
    fun search(keyword: String, sources: List<String>, page: Int = 1): List<Track> {
        val raw = mutableListOf<Track>()
        for (src in sources) {
            try {
                when (src) {
                    "netease" -> raw += searchNetease(keyword, page)
                    "joox" -> raw += searchJoox(keyword, page)
                    "kuwo" -> raw += searchKuwo(keyword, page)
                }
            } catch (_: Exception) {
                // 单个音源失败不影响其它音源
            }
        }
        // 跨音源去重：按 (name + artist) 判断，保留首次出现
        val seen = mutableSetOf<String>()
        return raw.filter { t ->
            val key = (t.name.trim() + "|" + t.artist.trim()).lowercase()
            seen.add(key)  // add 返回 false 表示已存在
        }
    }

    /** 芸朵（网易云）：GD Studio */
    private fun searchNetease(keyword: String, page: Int): List<Track> {
        val url = "$GD_API?types=search&source=netease&name=${enc(keyword)}&count=30&pages=$page"
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
                        artistEl.asJsonArray.joinToString("/") { e -> e.asString }
                    else -> artistEl.asString
                }
                out += Track(
                    id = o["id"].asString,
                    source = "netease",
                    name = o["name"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    artist = artist,
                    album = o["album"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    coverUrl = null,
                    picId = o["pic_id"]?.takeIf { !it.isJsonNull }?.asString,
                    keyword = keyword,
                    index = out.size + 1,
                    searchPage = page
                )
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** 绿鹅（JOOX）：apicx.asia */
    private fun searchJoox(keyword: String, page: Int): List<Track> {
        // apicx.asia 接口不支持翻页，page>1 时直接返回空（避免重复结果）
        if (page > 1) return emptyList()
        val url = "$JOOX_API?msg=${enc(keyword)}&token=$JOOX_TOKEN&br=4"
        val root = parseJson(get(url)) ?: return emptyList()
        if (root.get("code")?.asInt != 200) return emptyList()
        val songs = root.getAsJsonObject("data")?.getAsJsonArray("songs") ?: return emptyList()
        val out = mutableListOf<Track>()
        songs.forEach { el ->
            try {
                val o = el.asJsonObject
                val songMid = o.get("songmid")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val songId = o.get("歌曲ID")?.takeIf { !it.isJsonNull }?.asString ?: songMid
                val seq = o.get("序号")?.takeIf { !it.isJsonNull }?.asInt ?: (out.size + 1)
                out += Track(
                    id = songMid.ifEmpty { songId },
                    source = "joox",
                    name = o["歌曲名称"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    artist = o["歌手"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    album = o["专辑"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    coverUrl = null,
                    keyword = keyword,
                    index = seq,
                    searchPage = page
                )
            } catch (_: Exception) {
            }
        }
        return out
    }

    /**
     * 库窝（酷我）：官方 www.kuwo.cn 搜索接口
     * 参考自 musicdl kuwo.py 的 search 方法
     */
    private fun searchKuwo(keyword: String, page: Int): List<Track> {
        // 官方搜索参数（pn 从 0 开始）
        val params = mapOf(
            "vipver" to "1",
            "client" to "kt",
            "ft" to "music",
            "cluster" to "0",
            "strategy" to "2012",
            "encoding" to "utf8",
            "rformat" to "json",
            "mobi" to "1",
            "issubtitle" to "1",
            "show_copyright_off" to "1",
            "pn" to (page - 1).toString(),
            "rn" to "30",
            "all" to keyword
        )
        val queryStr = params.entries.joinToString("&") { (k, v) ->
            "$k=${enc(v)}"
        }
        val url = "$KUWO_SEARCH_URL?$queryStr"
        val root = parseJson(get(url, mapOf("User-Agent" to "Mozilla/5.0")))
            ?: return emptyList()
        val arr = root.getAsJsonObject("absite")?.getAsJsonArray("data")
            ?: root.getAsJsonArray("data")
            ?: return emptyList()
        val out = mutableListOf<Track>()
        arr.forEach { el ->
            try {
                val o = el.asJsonObject
                // MUSICRID 格式为 MUSIC_228908，取数字部分作为 id
                val musicRid = o["MUSICRID"]?.takeIf { !it.isJsonNull }?.asString ?: ""
                val rid = musicRid.removePrefix("MUSIC_").ifEmpty {
                    o["rid"]?.takeIf { !it.isJsonNull }?.asString ?: ""
                }
                if (rid.isEmpty()) return@forEach
                // hts_MVPIC 是缩略图，替换为高清封面
                val pic = o["hts_MVPIC"]?.takeIf { !it.isJsonNull }?.asString
                val cover = if (!pic.isNullOrEmpty()) {
                    pic.replace("/120/", "/500/")
                } else null
                out += Track(
                    id = rid,
                    source = "kuwo",
                    name = o["SONGNAME"]?.takeIf { !it.isJsonNull }?.asString
                        ?: o["name"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    artist = o["ARTIST"]?.takeIf { !it.isJsonNull }?.asString
                        ?.split("&")?.firstOrNull()?.trim()
                        ?: o["artist"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    album = o["ALBUM"]?.takeIf { !it.isJsonNull }?.asString
                        ?: o["album"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    coverUrl = cover,
                    keyword = keyword,
                    index = out.size + 1,
                    searchPage = page
                )
            } catch (_: Exception) {
            }
        }
        return out
    }

    // ==================== 解析播放链接 ====================

    /**
     * 获取封面 URL
     * - 芸朵：用 pic_id 调 types=pic 接口
     * - 库窝：搜索时已返回封面
     * - 绿鹅：用 gdstudio 搜索同名歌曲获取 pic_id，再取封面
     */
    fun resolveCover(track: Track): String? {
        if (!track.coverUrl.isNullOrEmpty()) return track.coverUrl
        if (track.source == "netease" && !track.picId.isNullOrEmpty()) {
            return try {
                val url = "$GD_API?types=pic&source=netease&id=${enc(track.picId)}"
                parseJson(get(url))?.get("url")?.takeIf { !it.isJsonNull }?.asString
            } catch (_: Exception) {
                null
            }
        }
        if (track.source == "joox") {
            return try {
                val searchUrl = "$GD_API?types=search&source=joox&name=${enc(track.name + " " + track.artist)}&count=5&pages=1"
                val arr = JsonParser.parseString(get(searchUrl)).asJsonArray
                for (el in arr) {
                    try {
                        val o = el.asJsonObject
                        val picId = o["pic_id"]?.takeIf { !it.isJsonNull }?.asString ?: continue
                        val picUrl = "$GD_API?types=pic&source=joox&id=${enc(picId)}"
                        val resolved = parseJson(get(picUrl))?.get("url")?.takeIf { !it.isJsonNull }?.asString
                        if (!resolved.isNullOrEmpty()) return resolved
                    } catch (_: Exception) {
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

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
        val root = parseJson(get(url))
            ?: throw RuntimeException("芸朵音源解析失败：无响应")
        val playUrl = root.get("url")?.takeIf { !it.isJsonNull }?.asString
            ?: throw RuntimeException("芸朵音源未返回链接（可能无版权）")
        if (playUrl.isEmpty()) throw RuntimeException("芸朵音源链接为空（可能无版权）")
        val fmt = formatFromUrl(playUrl)
        val actualBr = root.get("br")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        val label = if (fmt.ext == "flac") "FLAC无损" else "${actualBr}k"
        return ResolvedUrl(playUrl, fmt.ext, fmt.mime, label)
    }

    /**
     * 绿鹅：详情接口返回多档播放链接，按音质档位择优
     * 移除了 probeUrl 探测（审计 P0-1），直接取链接，不可用时由播放器报错
     */
    private fun resolveJoox(track: Track, quality: String): ResolvedUrl {
        val url = "$JOOX_API?msg=${enc(track.keyword)}&n=${track.index}&token=$JOOX_TOKEN&br=4"
        val root = parseJson(get(url))
            ?: throw RuntimeException("绿鹅音源解析失败：无响应")
        if (root.get("code")?.asInt != 200)
            throw RuntimeException("绿鹅音源解析失败：${root.get("msg") ?: "未知错误"}")
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
            if (u.isEmpty()) continue
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

    /**
     * 库窝（酷我）：官方 mobi.s DES 加密解析 + 兜底链
     * 参考自 musicdl kuwo.py 的 parse 方法
     *
     * 兜底链：mobi.s 官方 → nxinxz → xcloudv
     * 每级失败都带具体错误信息，不吞异常
     */
    private fun resolveKuwo(track: Track, quality: String): ResolvedUrl {
        // 音质档位映射到 mobi.s format 参数
        val (fmtParam, brParam) = when (quality) {
            "320k" -> "mp3" to "&br=320kmp3"
            "740k", "999k" -> "flac" to ""
            else -> "mp3" to ""  // 128k
        }
        val rid = track.id
        val errors = mutableListOf<String>()

        // 第一级：酷我官方 mobi.s DES 加密接口
        try {
            val query = "user=0&corp=kuwo&source=kwplayer_ar_5.1.0.0_B_jiakong_vh.apk" +
                    "&p2p=1&type=convert_url2&sig=0&format=$fmtParam&rid=$rid$brParam"
            val q = KuwoDes.encryptQuery(query)
            val url = "$KUWO_MOBI_URL?f=kuwo&q=$q"
            val resp = get(url, mapOf("User-Agent" to "okhttp/3.10.0"))
            // 响应为行式 key=value 格式
            val map = parseKuwoMobiResponse(resp)
            val playUrl = map["url"]
            if (!playUrl.isNullOrEmpty()) {
                val format = map["format"] ?: ""
                val bitrate = map["bitrate"] ?: ""
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
            errors.add("mobi.s 未返回链接（rid=$rid）")
        } catch (e: Exception) {
            errors.add("mobi.s 失败：${e.message}")
        }

        // 第二级：nxinxz 兜底
        try {
            val level = when (quality) {
                "740k", "999k" -> "lossless"
                "320k" -> "exhigh"
                else -> "standard"
            }
            val url = "$KUWO_FALLBACK_NXINXZ?id=$rid&level=$level&type=json"
            val root = parseJson(get(url, mapOf("User-Agent" to "Mozilla/5.0")))
            if (root != null && root.get("code")?.asInt == 200) {
                val playUrl = root.getAsJsonObject("data")?.get("url")
                    ?.takeIf { !it.isJsonNull }?.asString
                if (!playUrl.isNullOrEmpty()) {
                    val fmt = formatFromUrl(playUrl)
                    val label = if (fmt.ext == "flac") "FLAC无损" else quality
                    return ResolvedUrl(playUrl, fmt.ext, fmt.mime, label)
                }
            }
            errors.add("nxinxz 未返回链接（rid=$rid）")
        } catch (e: Exception) {
            errors.add("nxinxz 失败：${e.message}")
        }

        // 第三级：xcloudv 兜底
        try {
            val body = "action=url&songid=$rid&yz=5"
            val resp = post(KUWO_FALLBACK_XCLOUDV, body,
                mapOf("User-Agent" to "Mozilla/5.0"))
            val root = parseJson(resp)
            if (root != null) {
                val playUrl = root.get("url")?.takeIf { !it.isJsonNull }?.asString
                if (!playUrl.isNullOrEmpty()) {
                    val fmt = formatFromUrl(playUrl)
                    val label = if (fmt.ext == "flac") "FLAC无损" else quality
                    return ResolvedUrl(playUrl, fmt.ext, fmt.mime, label)
                }
            }
            errors.add("xcloudv 未返回链接（rid=$rid）")
        } catch (e: Exception) {
            errors.add("xcloudv 失败：${e.message}")
        }

        throw RuntimeException("库窝音源解析失败，已尝试全部兜底：${errors.joinToString("；")}")
    }

    /** 解析 mobi.s 行式响应：format=mp3\nbitrate=128\nurl=http://... */
    private fun parseKuwoMobiResponse(resp: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        resp.split("\n", "\r").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val key = trimmed.substring(0, eq).trim().lowercase()
                val value = trimmed.substring(eq + 1).trim()
                map[key] = value
            }
        }
        return map
    }

    // ==================== 歌词 ====================

    /**
     * 获取歌词文本
     * - 库窝：官方 newlyric.kuwo.cn XOR+zlib 接口
     * - 芸朵/绿鹅：GD Studio types=lyric 接口
     * @return 标准 LRC 格式文本（已去除 lrcx 增强标签）
     */
    fun fetchLyric(track: Track): String {
        return when (track.source) {
            "kuwo" -> fetchLyricKuwo(track.id)
            else -> fetchLyricGd(track)
        }
    }

    /** 库窝官方歌词：newlyric.kuwo.cn，XOR 加密请求，zlib+XOR+GB18030 解码响应 */
    private fun fetchLyricKuwo(rid: String): String {
        return try {
            val q = KuwoDes.buildLyricParams(rid)
            val url = "$KUWO_LYRIC_URL?$q"
            val bytes = client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", "okhttp/3.10.0")
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                resp.body?.bytes() ?: ByteArray(0)
            }
            if (bytes.isEmpty()) return ""
            val lrcx = KuwoDes.decodeLyrics(bytes)
            if (lrcx.isEmpty()) return ""
            KuwoDes.convertRawLrc(lrcx)
        } catch (_: Exception) {
            ""
        }
    }

    /** 芸朵/绿鹅歌词：GD Studio types=lyric */
    private fun fetchLyricGd(track: Track): String {
        return try {
            val url = "$GD_API?types=lyric&source=${track.source}&id=${enc(track.id)}"
            val root = parseJson(get(url)) ?: return ""
            root.get("lyric")?.takeIf { !it.isJsonNull }?.asString ?: ""
        } catch (_: Exception) {
            ""
        }
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
}
