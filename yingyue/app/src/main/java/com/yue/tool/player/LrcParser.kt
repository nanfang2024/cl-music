package com.yue.tool.player

/**
 * LRC 歌词解析
 * 支持 [mm:ss]、[mm:ss.xx]、[mm:ss.xxx] 时间标签，一行多标签
 */
data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {

    private val TAG_REGEX = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(raw: String): List<LrcLine> {
        val out = mutableListOf<LrcLine>()
        raw.lines().forEach { line ->
            val matches = TAG_REGEX.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val text = line.substring(matches.last().range.last + 1).trim()
            // 跳过空行与元数据（[ti:][ar:] 等无内容的行）
            if (text.isEmpty()) return@forEach
            matches.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                out += LrcLine(min * 60000 + sec * 1000 + frac, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
