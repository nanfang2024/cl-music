package com.yue.tool.api

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
 * 五音源聚合：
 * - 芸朵（netease）：GD Studio 音乐台 API
 * - 绿鹅（joox）：apicx.asia JOOX 接口（参考 musicsquare 项目）
 * - 库窝（kuwo）：oiapi.net 酷我接口（参考 musicsquare 项目）
 * - 酷狗（kugou）：官方搜索 + baka.plus 解析（参考 musicdl 项目）
 * - 咪咕（migu）：官方 H5 接口 + 响应解密（参考 musicdl 项目）
 */
object MusicApi {

    private const val GD_API = "https://music-api.gdstudio.xyz/api.php"
    private const val JOOX_API = "https://apicx.asia/api/joox_music"
    private const val JOOX_TOKEN = "f84ao9lMF_q7husBWRfgUw"
    private const val KUWO_API = "https://oiapi.net/api/Kuwo"
    private const val KUGOU_SEARCH = "http://mobilecdn.kugou.com/api/v3/search/song"
    private const val KUGOU_BAKA = "https://api.baka.plus/meting/"
    private const val MIGU_SEARCH = "https://c.musicapp.migu.cn/v1.0/content/search_all.do"
    private const val MIGU_LISTEN = "https://c.musicapp.migu.cn/strategy/listen-url/h5/v2.4"
    private val MIGU_KEY = "Jk8qzuePiJ1qE3mDYhLQ3T73DtDoAhLP".toByteArray(Charsets.US_ASCII)

    /** 咪咕请求公共头（H5 渠道） */
    private val MIGU_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36",
        "Origin" to "https://h5.nf.migu.cn",
        "Referer" to "https://h5.nf.migu.cn/",
        "ua" to "Android_migu",
        "version" to "6.8.8",
        "channel" to "014021I"
    )

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    /** 取原始字节（咪咕加密响应用） */
    private fun getBytes(url: String, headers: Map<String, String>): ByteArray {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    /** 不跟随重定向，返回 Location（酷狗 baka 解析用） */
    private fun getRedirect(url: String): String {
        val noRedirect = client.newBuilder().followRedirects(false).build()
        noRedirect.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build())
            .execute().use { resp ->
                val loc = resp.header("Location")
                    ?: resp.request.url.toString().takeIf { resp.isSuccessful }
                return loc ?: throw RuntimeException("无重定向目标（HTTP ${resp.code}）")
            }
    }

    private fun parse(s: String): JsonObject? = try {
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
                    "kugou" -> raw += searchKugou(keyword, page)
                    "migu" -> raw += searchMigu(keyword, page)
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
        val root = parse(get(url)) ?: return emptyList()
        if (root.get("code")?.asInt != 200) return emptyList()
        val songs = root.getAsJsonObject("data")?.getAsJsonArray("songs") ?: return emptyList()
        val out = mutableListOf<Track>()
        songs.forEach { el ->
            try {
                val o = el.asJsonObject
                val songMid = o.get("songmid")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val songId = o.get("歌曲ID")?.takeIf { !it.isJsonNull }?.asString ?: songMid
                // 关键修复：index 使用遍历时的实际序号，不依赖 API 返回的"序号"字段
                // 这样与详情接口的 n 参数严格对应
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

    /** 库窝（酷我）：oiapi.net */
    private fun searchKuwo(keyword: String, page: Int): List<Track> {
        val url = "$KUWO_API?msg=${enc(keyword)}&page=$page&limit=30"
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
                    index = out.size + 1,
                    searchPage = page
                )
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** 酷狗（kugou）：官方移动端搜索接口，封面在 trans_param.union_cover */
    private fun searchKugou(keyword: String, page: Int): List<Track> {
        val url = "$KUGOU_SEARCH?format=json&keyword=${enc(keyword)}&page=$page&pagesize=30"
        val root = parse(get(url, mapOf("User-Agent" to "Mozilla/5.0"))) ?: return emptyList()
        val data = root.getAsJsonObject("data") ?: return emptyList()
        val info = data.getAsJsonArray("info") ?: return emptyList()
        val out = mutableListOf<Track>()
        info.forEach { el ->
            try {
                val o = el.asJsonObject
                val hash = o["hash"]?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                // 封面模板：http://imge.kugou.com/stdmusic/{size}/xxx.jpg
                val coverTpl = o.getAsJsonObject("trans_param")
                    ?.get("union_cover")?.takeIf { !it.isJsonNull }?.asString
                val cover = coverTpl?.replace("{size}", "400")
                out += Track(
                    id = hash,
                    source = "kugou",
                    name = o["songname"]?.takeIf { !it.isJsonNull }?.asString
                        ?: o["filename"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    artist = o["singername"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    album = o["album_name"]?.takeIf { !it.isJsonNull }?.asString ?: "",
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

    /** 咪咕（migu）：官方 H5 搜索接口，id 存 "contentId|copyrightId" */
    private fun searchMigu(keyword: String, page: Int): List<Track> {
        val searchSwitch = """{"song":1,"album":0,"singer":0,"tagSong":1,"mvSong":0,"bestShow":1}"""
        val url = "$MIGU_SEARCH?text=${enc(keyword)}&pageNo=$page&pageSize=30" +
                "&isCopyright=1&sort=1&searchSwitch=${enc(searchSwitch)}"
        val root = parse(get(url, MIGU_HEADERS)) ?: return emptyList()
        if (root.get("code")?.takeIf { !it.isJsonNull }?.asString != "000000") return emptyList()
        val results = root.getAsJsonObject("songResultData")
            ?.getAsJsonArray("result") ?: return emptyList()
        val out = mutableListOf<Track>()
        results.forEach { el ->
            try {
                val o = el.asJsonObject
                val contentId = o["contentId"]?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                val copyrightId = o["copyrightId"]?.takeIf { !it.isJsonNull }?.asString ?: ""
                // 封面：取 imgItems 最大的一个
                var cover: String? = null
                runCatching {
                    cover = o.getAsJsonArray("imgItems")
                        ?.lastOrNull { !it.isJsonNull }
                        ?.asJsonObject?.get("img")?.asString
                }
                val singers = o.getAsJsonArray("singers")
                    ?.joinToString(" / ") { s -> s.asJsonObject["name"]?.asString ?: "" }
                    ?.takeIf { it.isNotEmpty() } ?: ""
                val albums = o.getAsJsonArray("albums")
                    ?.firstOrNull { !it.isJsonNull }
                    ?.asJsonObject?.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                out += Track(
                    id = "$contentId|$copyrightId",
                    source = "migu",
                    name = o["name"]?.takeIf { !it.isJsonNull }?.asString ?: "",
                    artist = singers,
                    album = albums,
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
     * - 库窝：搜索时已返回 picture 字段
     * - 绿鹅：用 gdstudio 搜索同名歌曲获取 pic_id，再取封面
     */
    fun resolveCover(track: Track): String? {
        if (!track.coverUrl.isNullOrEmpty()) return track.coverUrl
        if (track.source == "netease" && !track.picId.isNullOrEmpty()) {
            return try {
                val url = "$GD_API?types=pic&source=netease&id=${enc(track.picId)}"
                parse(get(url))?.get("url")?.takeIf { !it.isJsonNull }?.asString
            } catch (_: Exception) {
                null
            }
        }
        if (track.source == "joox") {
            return try {
                // 用 gdstudio 搜索 JOOX 同名歌曲，获取 pic_id
                val searchUrl = "$GD_API?types=search&source=joox&name=${enc(track.name + " " + track.artist)}&count=5&pages=1"
                val arr = JsonParser.parseString(get(searchUrl)).asJsonArray
                for (el in arr) {
                    try {
                        val o = el.asJsonObject
                        val picId = o["pic_id"]?.takeIf { !it.isJsonNull }?.asString ?: continue
                        val picUrl = "$GD_API?types=pic&source=joox&id=${enc(picId)}"
                        val resolved = parse(get(picUrl))?.get("url")?.takeIf { !it.isJsonNull }?.asString
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
            "kugou" -> resolveKugou(track, quality)
            "migu" -> resolveMigu(track, quality)
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
            ?: throw RuntimeException("芸朵音源解析失败：无响应")
        val playUrl = root.get("url")?.takeIf { !it.isJsonNull }?.asString
            ?: throw RuntimeException("芸朵音源未返回链接（可能无版权）")
        val fmt = formatFromUrl(playUrl)
        val actualBr = root.get("br")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        val label = if (fmt.ext == "flac") "FLAC无损" else "${actualBr}k"
        return ResolvedUrl(playUrl, fmt.ext, fmt.mime, label)
    }

    /** 绿鹅：详情接口返回多档播放链接，按音质档位择优探测 */
    private fun resolveJoox(track: Track, quality: String): ResolvedUrl {
        val url = "$JOOX_API?msg=${enc(track.keyword)}&n=${track.index}&token=$JOOX_TOKEN&br=4"
        val root = parse(get(url))
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
        var lastError = "绿鹅音源所有音质链接均不可用"
        for (name in tiers) {
            val u = links.get(name)?.takeIf { !it.isJsonNull }?.asString ?: continue
            if (!probeUrl(u)) {
                lastError = "绿鹅音源链接不可用：$name"
                continue
            }
            val fmt = formatFromUrl(u)
            val label = if (fmt.ext == "flac") {
                if (name.contains("母带")) "母带无损" else "FLAC无损"
            } else {
                name.substringAfterLast(" ")
            }
            return ResolvedUrl(u, fmt.ext, fmt.mime, label)
        }
        throw RuntimeException(lastError)
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
            ?: throw RuntimeException("库窝音源解析失败：无响应")
        val data = root.getAsJsonObject("data")
            ?: throw RuntimeException("库窝音源未返回数据：${root.get("msg") ?: "未知错误"}")
        val playUrl = data.get("url")?.takeIf { !it.isJsonNull }?.asString
            ?: throw RuntimeException("库窝音源未返回链接（可能无版权）")
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

    /** 酷狗：三级解析链 baka(分档) → cocodownloader(无损) → lzmhhh(无损) */
    private fun resolveKugou(track: Track, quality: String): ResolvedUrl {
        val ua = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

        // 链1：baka meting，支持音质分档（br=128/320/2000），302 到官方 CDN
        try {
            val br = when (quality) {
                "128k" -> "128"
                "320k" -> "320"
                else -> "2000"   // 740k/999k 均取无损
            }
            val playUrl = getRedirect("$KUGOU_BAKA?server=kugou&type=url&id=${enc(track.id)}&br=$br")
            if (playUrl.startsWith("http")) {
                val fmt = formatFromUrl(playUrl)
                val label = when {
                    fmt.ext == "flac" -> "FLAC无损"
                    else -> quality
                }
                return ResolvedUrl(playUrl, fmt.ext, fmt.mime, label)
            }
        } catch (_: Exception) {
        }

        // 链2：cocodownloader，仅返回无损
        try {
            val root = parse(
                get("https://cocodownloader.markqq.com/api/url?id=${enc(track.id)}&provider=kugou", ua)
            )
            val u = root?.get("url")?.takeIf { !it.isJsonNull }?.asString
            if (!u.isNullOrEmpty() && u.startsWith("http")) {
                val fmt = formatFromUrl(u)
                return ResolvedUrl(u, fmt.ext, fmt.mime, "FLAC无损")
            }
        } catch (_: Exception) {
        }

        // 链3：lzmhhh，仅返回无损
        try {
            val body = okhttp3.FormBody.Builder()
                .add("id", track.id)
                .add("type", "kg")
                .build()
            val req = Request.Builder()
                .url("https://music.lzmhhh.com/api/music/url")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", "https://music.lzmhhh.com/")
                .header("Origin", "https://music.lzmhhh.com")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                val u = parse(text)?.get("data")?.takeIf { !it.isJsonNull }?.asString
                if (!u.isNullOrEmpty() && u.startsWith("http")) {
                    val fmt = formatFromUrl(u)
                    return ResolvedUrl(u, fmt.ext, fmt.mime, "FLAC无损")
                }
            }
        } catch (_: Exception) {
        }

        throw RuntimeException("酷狗音源解析失败：所有链路均不可用")
    }

    /** 咪咕：官方 listen-url 接口（响应加密），toneFlag=PQ/HQ/SQ */
    private fun resolveMigu(track: Track, quality: String): ResolvedUrl {
        val parts = track.id.split("|")
        val contentId = parts.getOrNull(0) ?: throw RuntimeException("咪咕音源参数缺失")
        val copyrightId = parts.getOrNull(1) ?: ""
        val toneFlag = when (quality) {
            "128k" -> "PQ"
            "320k" -> "HQ"
            else -> "SQ"      // 740k/999k 均取无损
        }
        val url = "$MIGU_LISTEN?contentId=$contentId&copyrightId=$copyrightId" +
                "&resourceType=2&netType=01&toneFlag=$toneFlag&scene=&lowerQualityContentId=$contentId"
        val headers = MIGU_HEADERS + mapOf(
            "birth" to "h5page",
            "signature" to "1"
        )
        val raw = getBytes(url, headers)
        val text = decryptMigu(raw)
        val root = parse(text)
            ?: throw RuntimeException("咪咕音源解析失败：无法解密响应")
        if (root.get("code")?.takeIf { !it.isJsonNull }?.asString != "000000") {
            throw RuntimeException("咪咕音源未返回链接（可能无版权）")
        }
        val playUrl = root.getAsJsonObject("data")?.get("url")
            ?.takeIf { !it.isJsonNull }?.asString
            ?: throw RuntimeException("咪咕音源未返回链接（可能无版权）")
        val fmt = formatFromUrl(playUrl)
        val label = if (fmt.ext == "flac") "FLAC无损" else quality
        return ResolvedUrl(playUrl, fmt.ext, fmt.mime, label)
    }

    /**
     * 咪咕响应解密（参考 musicdl）：
     * 密文 = AB CD 01 + seed + payload，明文字节 = (payload[i] + seed - KEY[i%len]) & 0xFF
     */
    private fun decryptMigu(raw: ByteArray): String {
        if (raw.size < 4) return ""
        if (!(raw[0] == 0xAB.toByte() && raw[1] == 0xCD.toByte() && raw[2] == 0x01.toByte())) {
            // 未加密，直接按 UTF-8 文本返回
            return String(raw, Charsets.UTF_8)
        }
        val seed = raw[3].toInt() and 0xFF
        val out = ByteArray(raw.size - 4)
        for (i in out.indices) {
            val k = MIGU_KEY[i % MIGU_KEY.size].toInt() and 0xFF
            out[i] = ((raw[i + 4].toInt() and 0xFF) + seed - k and 0xFF).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

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
