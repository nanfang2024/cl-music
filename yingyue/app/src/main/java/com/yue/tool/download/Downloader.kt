package com.yue.tool.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yue.tool.api.MusicApi
import com.yue.tool.api.ResolvedUrl
import com.yue.tool.api.Track
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object Downloader {

    const val DIR_NAME = "映月"

    fun interface ProgressListener {
        fun onProgress(percent: Int)
    }

    /**
     * 下载音频文件到公共音乐目录（映月/）。
     * @return 结果 Uri（Android 10+ 为 content://，以下为 file://）
     */
    fun download(
        context: Context,
        track: Track,
        resolved: ResolvedUrl,
        listener: ProgressListener? = null
    ): Uri {
        val fileName = sanitize("${track.name}-${track.artist}.${resolved.ext}")
        val client = MusicApi.client

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 走 MediaStore
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, resolved.mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + DIR_NAME)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values)
                ?: throw RuntimeException("无法创建下载任务")

            try {
                client.newCall(Request.Builder().url(resolved.url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("下载失败: HTTP ${resp.code}")
                    val body = resp.body ?: throw RuntimeException("下载失败: 空响应")
                    val total = body.contentLength()
                    var read = 0L
                    resolver.openOutputStream(itemUri)?.use { out: OutputStream ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            var n = input.read(buf)
                            var lastPct = -1
                            while (n >= 0) {
                                out.write(buf, 0, n)
                                read += n
                                if (total > 0) {
                                    val pct = (read * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        listener?.onProgress(pct)
                                    }
                                }
                                n = input.read(buf)
                            }
                            out.flush()
                        }
                    } ?: throw RuntimeException("无法写入文件")
                }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
                return itemUri
            } catch (e: Exception) {
                try {
                    resolver.delete(itemUri, null, null)
                } catch (_: Exception) {
                }
                throw e
            }
        } else {
            // Android 8/9 直接写公共目录
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                DIR_NAME
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)

            client.newCall(Request.Builder().url(resolved.url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("下载失败: HTTP ${resp.code}")
                val body = resp.body ?: throw RuntimeException("下载失败: 空响应")
                val total = body.contentLength()
                var read = 0L
                FileOutputStream(file).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var n = input.read(buf)
                        var lastPct = -1
                        while (n >= 0) {
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    listener?.onProgress(pct)
                                }
                            }
                            n = input.read(buf)
                        }
                        out.flush()
                    }
                }
            }
            return Uri.fromFile(file)
        }
    }

    /** 清理文件名中的非法字符 */
    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().take(120).ifEmpty { "audio" }
}
