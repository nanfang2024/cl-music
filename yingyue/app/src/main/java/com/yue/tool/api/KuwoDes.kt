package com.yue.tool.api

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64
import java.util.zip.Inflater

/**
 * 酷我自研 DES 变体 + 歌词 XOR 加解密
 * 移植自 musicdl 项目 kuwoutils.py，已实测逐字节匹配
 *
 * 要点：
 * - 非标准 DES，S 盒与置换表均为酷我定制
 * - 密钥 DES 用 "ylzsxkwm"，歌词用 "yeelion"
 * - Kotlin Long 为 64 位有符号，与 Python 的 &MASK64 语义一致
 */
object KuwoDes {

    private const val SONG_KEY = "ylzsxkwm"
    private val LYRIC_KEY = "yeelion".toByteArray(Charsets.US_ASCII)

    // ========== 查找表 ==========
    private val ARRAYLS = longArrayOf(
        1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1
    )
    private val ARRAYLSMASK = longArrayOf(0, 0x100001, 0x300003)
    private val ARRAYE = longArrayOf(
        31, 0, 1, 2, 3, 4, -1, -1, 3, 4, 5, 6, 7, 8, -1, -1,
        7, 8, 9, 10, 11, 12, -1, -1, 11, 12, 13, 14, 15, 16, -1, -1,
        15, 16, 17, 18, 19, 20, -1, -1, 19, 20, 21, 22, 23, 24, -1, -1,
        23, 24, 25, 26, 27, 28, -1, -1, 27, 28, 29, 30, 31, 30, -1, -1
    )
    private val ARRAYIP1 = longArrayOf(
        39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24
    )
    private val ARRAYIP2 = longArrayOf(
        57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
        61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
        56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2,
        60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6
    )
    private val ARRAYMASK = run {
        // ARRAYMASK[n] = 1 << n, 最后一个为 -(1<<63)
        LongArray(64) { n -> if (n == 63) Long.MIN_VALUE else 1L shl n }
    }
    private val ARRAYP = longArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24
    )
    private val ARRAYPC1 = longArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1,
        58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35, 62, 54, 46, 38,
        30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36,
        28, 20, 12, 4, 27, 19, 11, 3
    )
    private val ARRAYPC2 = longArrayOf(
        13, 16, 10, 23, 0, 4, -1, -1, 2, 27, 14, 5, 20, 9, -1, -1,
        22, 18, 11, 3, 25, 7, -1, -1, 15, 6, 26, 19, 12, 1, -1, -1,
        40, 51, 30, 36, 46, 54, -1, -1, 29, 39, 50, 44, 32, 47, -1, -1,
        43, 48, 38, 55, 33, 52, -1, -1, 45, 41, 49, 35, 28, 31, -1, -1
    )
    private val MATRIXNSBOX = arrayOf(
        longArrayOf(14, 4, 3, 15, 2, 13, 5, 3, 13, 14, 6, 9, 11, 2, 0, 5, 4, 1, 10, 12, 15, 6, 9, 10, 1, 8, 12, 7, 8, 11, 7, 0, 0, 15, 10, 5, 14, 4, 9, 10, 7, 8, 12, 3, 13, 1, 3, 6, 15, 12, 6, 11, 2, 9, 5, 0, 4, 2, 11, 14, 1, 7, 8, 13),
        longArrayOf(15, 0, 9, 5, 6, 10, 12, 9, 8, 7, 2, 12, 3, 13, 5, 2, 1, 14, 7, 8, 11, 4, 0, 3, 14, 11, 13, 6, 4, 1, 10, 15, 3, 13, 12, 11, 15, 3, 6, 0, 4, 10, 1, 7, 8, 4, 11, 14, 13, 8, 0, 6, 2, 15, 9, 5, 7, 1, 10, 12, 14, 2, 5, 9),
        longArrayOf(10, 13, 1, 11, 6, 8, 11, 5, 9, 4, 12, 2, 15, 3, 2, 14, 0, 6, 13, 1, 3, 15, 4, 10, 14, 9, 7, 12, 5, 0, 8, 7, 13, 1, 2, 4, 3, 6, 12, 11, 0, 13, 5, 14, 6, 8, 15, 2, 7, 10, 8, 15, 4, 9, 11, 5, 9, 0, 14, 3, 10, 7, 1, 12),
        longArrayOf(7, 10, 1, 15, 0, 12, 11, 5, 14, 9, 8, 3, 9, 7, 4, 8, 13, 6, 2, 1, 6, 11, 12, 2, 3, 0, 5, 14, 10, 13, 15, 4, 13, 3, 4, 9, 6, 10, 1, 12, 11, 0, 2, 5, 0, 13, 14, 2, 8, 15, 7, 4, 15, 1, 10, 7, 5, 6, 12, 11, 3, 8, 9, 14),
        longArrayOf(2, 4, 8, 15, 7, 10, 13, 6, 4, 1, 3, 12, 11, 7, 14, 0, 12, 2, 5, 9, 10, 13, 0, 3, 1, 11, 15, 5, 6, 8, 9, 14, 14, 11, 5, 6, 4, 1, 3, 10, 2, 12, 15, 0, 13, 2, 8, 5, 11, 8, 0, 15, 7, 14, 9, 4, 12, 7, 10, 9, 1, 13, 6, 3),
        longArrayOf(12, 9, 0, 7, 9, 2, 14, 1, 10, 15, 3, 4, 6, 12, 5, 11, 1, 14, 13, 0, 2, 8, 7, 13, 15, 5, 4, 10, 8, 3, 11, 6, 10, 4, 6, 11, 7, 9, 0, 6, 4, 2, 13, 1, 9, 15, 3, 8, 15, 3, 1, 14, 12, 5, 11, 0, 2, 12, 14, 7, 5, 10, 8, 13),
        longArrayOf(4, 1, 3, 10, 15, 12, 5, 0, 2, 11, 9, 6, 8, 7, 6, 9, 11, 4, 12, 15, 0, 3, 10, 5, 14, 13, 7, 8, 13, 14, 1, 2, 13, 6, 14, 9, 4, 1, 2, 14, 11, 13, 5, 0, 1, 10, 8, 3, 0, 11, 3, 5, 9, 4, 15, 2, 7, 8, 12, 15, 10, 7, 6, 12),
        longArrayOf(13, 7, 10, 0, 6, 9, 5, 15, 8, 4, 3, 10, 11, 14, 12, 5, 2, 11, 9, 6, 15, 12, 0, 3, 4, 1, 14, 13, 1, 2, 7, 8, 1, 2, 12, 15, 10, 4, 0, 3, 13, 14, 6, 9, 7, 8, 9, 6, 15, 1, 5, 12, 3, 10, 14, 5, 8, 7, 11, 0, 4, 13, 2, 11)
    )

    // ========== 核心运算 ==========

    private fun u64(x: Long): Long = x
    private fun u32(x: Long): Long = x and 0xFFFFFFFFL

    /** 位变换：按 arr_int 的索引从 l 中取位并合并 */
    private fun bittransform(arrInt: LongArray, n: Int, l: Long): Long {
        var result = 0L
        for (i in 0 until n) {
            val idx = arrInt[i]
            if (idx >= 0 && (l and ARRAYMASK[idx.toInt()]) != 0L) {
                result = result or ARRAYMASK[i]
            }
        }
        return u64(result)
    }

    /** DES 64 位块加密 */
    private fun des64(longs: LongArray, l: Long): Long {
        val pR = LongArray(8)
        val out = bittransform(ARRAYIP2, 64, l)
        var pSource0 = u32(out)
        var pSource1 = u32(out ushr 32)
        for (i in 0 until 16) {
            val r = bittransform(ARRAYE, 64, pSource1) xor longs[i]
            for (j in 0 until 8) {
                pR[j] = (r ushr (j * 8)) and 0xFF
            }
            var sOut = 0L
            for (sbi in 7 downTo 0) {
                sOut = (sOut shl 4) or (MATRIXNSBOX[sbi][pR[sbi].toInt()] and 0xF)
            }
            val newP1 = u32(pSource0 xor bittransform(ARRAYP, 32, sOut))
            pSource0 = pSource1
            pSource1 = newP1
        }
        val tmp = pSource0; pSource0 = pSource1; pSource1 = tmp
        val result = (pSource1 shl 32) or (pSource0 and 0xFFFFFFFFL)
        return u64(bittransform(ARRAYIP1, 64, result))
    }

    /** 生成子密钥 */
    private fun subkeys(l: Long, longs: LongArray, mode: Int) {
        var l2 = bittransform(ARRAYPC1, 56, l)
        val states = LongArray(17)
        states[0] = l2
        for (i in 0 until 16) {
            val r = ARRAYLS[i].toInt()
            val mask = ARRAYLSMASK[r]
            val left = u64((states[i] and mask) shl (28 - r))
            val right = u64((states[i] and u64(mask.inv())) ushr r)
            states[i + 1] = u64(left or right)
            l2 = states[i + 1]
        }
        for (i in 0 until 16) {
            longs[i] = bittransform(ARRAYPC2, 64, states[i + 1])
        }
        if (mode == 1) {
            // 反转子密钥数组
            for (i in 0 until 8) {
                val t = longs[i]; longs[i] = longs[15 - i]; longs[15 - i] = t
            }
        }
    }

    /** 加密/解密（mode=0 加密，mode=1 解密） */
    private fun crypt(msg: ByteArray, key: ByteArray, mode: Int): ByteArray {
        var l = 0L
        for (i in key.indices) {
            l = l or ((key[i].toLong() and 0xFF) shl (i * 8))
        }
        l = u64(l)
        val arrLong1 = LongArray(16)
        subkeys(l, arrLong1, mode)

        val j = msg.size / 8
        val arrLong2 = LongArray(j)
        for (m in 0 until j) {
            var v = 0L
            for (n in 0 until 8) {
                v = v or ((msg[n + m * 8].toLong() and 0xFF) shl (n * 8))
            }
            arrLong2[m] = u64(v)
        }

        val arrLong3 = LongArray(j + 1)
        for (i1 in 0 until j) {
            arrLong3[i1] = des64(arrLong1, arrLong2[i1])
        }

        // 处理尾部不足 8 字节的块
        val tailLen = msg.size % 8
        if (tailLen != 0 || mode == 0) {
            var l2 = 0L
            for (i in 0 until tailLen) {
                l2 = l2 or ((msg[j * 8 + i].toLong() and 0xFF) shl (i * 8))
            }
            l2 = u64(l2)
            arrLong3[j] = des64(arrLong1, l2)
        }

        // 转回字节数组
        val outBytes = ByteArray((j + 1) * 8)
        for (i3 in arrLong3.indices) {
            for (i6 in 0 until 8) {
                outBytes[i3 * 8 + i6] = ((arrLong3[i3] ushr (i6 * 8)) and 0xFF).toByte()
            }
        }
        return outBytes
    }

    // ========== 公开接口 ==========

    /** 加密解析请求参数，返回 base64（作为 mobi.s 的 q 参数） */
    fun encryptQuery(query: String): String {
        val ct = crypt(query.toByteArray(Charsets.UTF_8), SONG_KEY.toByteArray(Charsets.US_ASCII), 0)
        return Base64.getEncoder().encodeToString(ct)
    }

    // ========== 歌词接口 ==========

    /** XOR 加解密 */
    private fun xorCrypto(data: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (i in data.indices) {
            out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return out
    }

    /** 构造歌词请求参数：XOR 加密后 base64 */
    fun buildLyricParams(musicId: String): String {
        val params = "user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_$musicId&lrcx=1"
        val buf = params.toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(xorCrypto(buf, LYRIC_KEY))
    }

    /** 解析歌词接口响应，返回 lrcx 原始文本 */
    fun decodeLyrics(buf: ByteArray): String {
        val prefix = "tp=content".toByteArray(Charsets.US_ASCII)
        if (buf.size < prefix.size + 4) return ""
        for (i in 0 until prefix.size) {
            if (buf[i] != prefix[i]) return ""
        }
        // 找 \r\n\r\n 分隔点
        var split = -1
        for (i in 0 until buf.size - 3) {
            if (buf[i].toInt() and 0xFF == 13 && buf[i + 1].toInt() and 0xFF == 10 && buf[i + 2].toInt() and 0xFF == 13 && buf[i + 3].toInt() and 0xFF == 10) {
                split = i
                break
            }
        }
        if (split < 0) return ""
        return try {
            val start = split + 4
            val inflated = inflate(buf.copyOfRange(start, buf.size))
            val lrcB64 = String(inflated, Charsets.UTF_8)
            val decoded = Base64.getDecoder().decode(lrcB64)
            val decrypted = xorCrypto(decoded, LYRIC_KEY)
            // 尝试 GB18030，失败则用 UTF-8
            decodeCharset(decrypted)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 将 lrcx 增强格式转换为标准 LRC 文本
     * 简化策略：去除所有 <...> 标签，保留 [mm:ss.xxx] 时间戳和文本
     */
    fun convertRawLrc(raw: String): String {
        val tagRx = Regex("<[^>]*>")
        return raw.lineSequence()
            .map { line ->
                // 保留元数据行（如 [kuwo:...] [ti:...] [ver:...]）
                if (line.startsWith("[") && !line.contains("<")) {
                    line
                } else {
                    // 去除所有 <...> 标签
                    line.replace(tagRx, "")
                }
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(4096)
        try {
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun decodeCharset(data: ByteArray): String {
        // 酷我歌词用 GB18030 编码
        return try {
            String(data, Charset.forName("GB18030"))
        } catch (_: Exception) {
            try {
                String(data, Charset.forName("GBK"))
            } catch (_: Exception) {
                String(data, Charsets.UTF_8)
            }
        }
    }
}
