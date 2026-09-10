package com.yue.tool.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import com.yue.tool.R
import com.yue.tool.api.MusicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量级图片加载器：OkHttp 异步下载 + 内存缓存 + 圆角
 * 不引入 Glide/Fresco 等第三方库
 */
object ImageLoader {

    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val loading = ConcurrentHashMap<String, Boolean>()

    fun load(view: ImageView, url: String?) {
        if (url.isNullOrEmpty()) {
            view.setImageResource(R.drawable.ic_cover_placeholder)
            return
        }
        // 命中内存缓存
        cache[url]?.let { bmp ->
            view.setImageBitmap(bmp)
            return
        }
        // 先设占位图
        view.setImageResource(R.drawable.ic_cover_placeholder)
        // 防重复加载
        if (loading[url] == true) return
        loading[url] = true

        val tag = url
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bmp = downloadBitmap(url)
                cache[url] = bmp
                withContext(Dispatchers.Main) {
                    // 检查 view 是否还被绑定到同一个 URL
                    if (view.tag == tag) {
                        view.setImageBitmap(bmp)
                    }
                }
            } catch (_: Exception) {
                // 加载失败保持占位图
            } finally {
                loading.remove(url)
            }
        }
    }

    private fun downloadBitmap(url: String): Bitmap {
        MusicApi.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: throw RuntimeException("空响应")
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
}
