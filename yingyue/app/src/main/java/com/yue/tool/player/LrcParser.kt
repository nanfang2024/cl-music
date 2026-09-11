package com.yue.tool.player

/**
 * LRC 歌词解析器
 * 解析标准 LRC 格式 [mm:ss.xxx]文本，支持多时间戳行
 *
 * 审计 P2-2：convertRawLrc 已在 KuwoDes 中简化为去除所有 <...> 标签
 * 此处仅负责 LRC 时间戳解析与滚动定位
 */
object LrcParser {

    data class LrcLine(
        val timeMs: Int,
        val text: String
    )

    private val LRC_TIME_REGEX = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /**
     * 解析 LRC 文本为歌词行列表
     * 支持一行多个时间戳：[00:01.00][00:15.00]歌词文本
     */
    fun parse(lrcText: String): List<LrcLine> {
        if (lrcText.isBlank()) return emptyList()
        val lines = mutableListOf<LrcLine>()
        lrcText.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            // 查找所有时间戳
            val matches = LRC_TIME_REGEX.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            // 提取歌词文本（去掉所有时间戳部分）
            val text = matches.fold(line) { acc, m ->
                acc.replaceRange(m.range.first, m.range.last + 1, "")
            }.trim()
            // 为每个时间戳创建一行
            for (m in matches) {
                val min = m.groupValues[1].toIntOrNull() ?: 0
                val sec = m.groupValues[2].toIntOrNull() ?: 0
                val msStr = m.groupValues.getOrNull(3)
                val ms = when {
                    msStr.isNullOrEmpty() -> 0
                    msStr.length == 1 -> msStr.toInt() * 100
                    msStr.length == 2 -> msStr.toInt() * 10
                    else -> msStr.take(3).toInt()
                }
                val timeMs = (min * 60 + sec) * 1000 + ms
                lines.add(LrcLine(timeMs, text))
            }
        }
        // 按时间排序
        return lines.sortedBy { it.timeMs }
    }

    /**
     * 根据当前播放位置，找到应该高亮显示的歌词行索引
     * @param lines 歌词列表（已按时间排序）
     * @param currentMs 当前播放位置（毫秒）
     * @return 高亮行索引，-1 表示无匹配
     */
    fun findCurrentLine(lines: List<LrcLine>, currentMs: Int): Int {
        if (lines.isEmpty()) return -1
        // 二分查找最后一个 timeMs <= currentMs 的行
        var lo = 0
        var hi = lines.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= currentMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }
}
