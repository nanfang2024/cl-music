package com.yue.tool.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.yue.tool.api.MusicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 极简封面图加载器（不依赖 Glide/Picasso，避免引入库）
 * - 线程：IO 下载 + Main 设置
 * - 有 LRU 内存缓存（10MB）
 * - 通过 tag 防止错乱（快速滑动时错位）
 */
object CoverLoader {

    private val cache: LruCache<String, Bitmap> = run {
        val max = (Runtime.getRuntime().maxMemory() / 8L).toInt().coerceAtMost(10 * 1024 * 1024)
        object : LruCache<String, Bitmap>(max) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }

    fun load(scope: CoroutineScope, url: String?, target: ImageView, placeholderRes: Int) {
        val ctx = target.context
        target.tag = url
        if (url.isNullOrEmpty()) {
            target.setImageResource(placeholderRes)
            return
        }
        val cached = cache.get(url)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        target.setImageResource(placeholderRes)
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    MusicApi.client.newCall(Request.Builder().url(url).build()).execute()
                        .use { resp ->
                            if (!resp.isSuccessful) return@use null
                            resp.body?.byteStream()?.use { stream ->
                                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                                BitmapFactory.decodeStream(stream, null, opts)
                            }
                        }
                }.getOrNull()
            } ?: return@launch
            cache.put(url, bmp)
            // 只有 tag 没变（即还是这条请求）才设置，避免错乱
            if (target.tag == url) target.setImageBitmap(bmp)
        }
    }
}
