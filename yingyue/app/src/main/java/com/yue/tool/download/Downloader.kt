package com.yue.tool.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object Downloader {

    const val MUSIC_DIR = "映月"

    data class Progress(val percent: Int, val received: Long, val total: Long)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun safeName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .trim()
            .take(80)
            .ifEmpty { "未命名" }

    suspend fun download(
        context: Context,
        url: String,
        displayName: String,
        onProgress: (Progress) -> Unit
    ): Uri = withContext(Dispatchers.IO) {
        val format = formatFromUrl(url)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("下载内容为空")
            val total = body.contentLength()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, displayName, format, body.byteStream(), total, onProgress)
            } else {
                saveLegacyFile(context, displayName, format, body.byteStream(), total, onProgress)
            }
        }
    }

    data class AudioFormat(val ext: String, val mime: String)

    /**
     * 根据真实下载链接推断音频格式：
     * 高音质（740k/999k）通常返回 flac / m4a，普通音质为 mp3
     */
    fun formatFromUrl(url: String): AudioFormat = when {
        url.contains(".flac", ignoreCase = true) -> AudioFormat("flac", "audio/flac")
        url.contains(".m4a", ignoreCase = true) -> AudioFormat("m4a", "audio/mp4")
        url.contains(".ogg", ignoreCase = true) -> AudioFormat("ogg", "audio/ogg")
        url.contains(".wav", ignoreCase = true) -> AudioFormat("wav", "audio/wav")
        else -> AudioFormat("mp3", "audio/mpeg")
    }

    private fun saveViaMediaStore(
        context: Context,
        displayName: String,
        format: AudioFormat,
        input: java.io.InputStream,
        total: Long,
        onProgress: (Progress) -> Unit
    ): Uri {
        val resolver = context.contentResolver

        fun insertUri(name: String): Uri? {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put("mime_type", format.mime) // MediaStore.MediaColumns.MIME_TYPE 的列名
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + MUSIC_DIR)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            return resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        }

        var name = "$displayName.${format.ext}"
        var uri = insertUri(name)
        var n = 1
        while (uri == null) {
            name = "$displayName ($n).${format.ext}"
            uri = insertUri(name)
            n++
            if (n > 99) throw IOException("无法创建下载文件")
        }

        try {
            resolver.openOutputStream(uri)?.use { out ->
                copyStream(input, out, total, onProgress)
            } ?: throw IOException("无法打开输出流")

            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveLegacyFile(
        context: Context,
        displayName: String,
        format: AudioFormat,
        input: java.io.InputStream,
        total: Long,
        onProgress: (Progress) -> Unit
    ): Uri {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            MUSIC_DIR
        )
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建目录 ${dir.absolutePath}")

        var file = File(dir, "$displayName.${format.ext}")
        var n = 1
        while (file.exists()) {
            file = File(dir, "$displayName ($n).${format.ext}")
            n++
        }

        file.outputStream().use { out -> copyStream(input, out, total, onProgress) }

        MediaScannerConnection.scanFile(
            context, arrayOf(file.absolutePath), arrayOf(format.mime), null
        )
        return Uri.fromFile(file)
    }

    private fun copyStream(
        input: java.io.InputStream,
        out: java.io.OutputStream,
        total: Long,
        onProgress: (Progress) -> Unit
    ) {
        val buf = ByteArray(64 * 1024)
        var received = 0L
        var lastPct = -1
        while (true) {
            val read = input.read(buf)
            if (read == -1) break
            out.write(buf, 0, read)
            received += read
            val pct = if (total > 0) (received * 100 / total).toInt() else -1
            if (pct != lastPct) {
                lastPct = pct
                onProgress(Progress(pct, received, total))
            }
        }
        out.flush()
    }
}
