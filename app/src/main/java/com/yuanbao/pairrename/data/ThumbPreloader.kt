package com.yuanbao.pairrename.data

import android.content.Context
import android.net.Uri
import coil.ImageLoader
import coil.request.ImageRequest
import com.yuanbao.pairrename.model.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 扫描完成后**提前**把前若干张缩略图解码进内存缓存。
 *
 * 为什么需要它：列表刚出现时 Coil 才开始解码，用户看到的就是一片转圈；
 * 尤其照片大、设备慢的时候，转圈会持续好几秒 —— 表现就是"卡得要命、
 * 非要操作一下才出图"。提前解码后，卡片首次显示时直接命中缓存，瞬间出图。
 *
 * 只预加载前面几十张（用户一屏能看到的量），后面的边滚边加载，
 * 不浪费内存也不阻塞。失败完全静默，因为这只是加速。
 */
object ThumbPreloader {

    /** 预加载多少张。一屏通常看不到这么多。 */
    private const val COUNT = 40
    /** 缩略图边长，需与 ImageCard 内的一致。 */
    private const val PX = 320

    /** 连续调用时的节流：避免每次刷新都重复解码。 */
    @Volatile
    private var lastAt = 0L
    @Volatile
    private var lastKey = ""

    private const val THROTTLE_MS = 3_000L

    suspend fun preload(context: Context, loader: ImageLoader, items: List<ImageItem>) {
        if (items.isEmpty()) return
        val key = items.firstOrNull()?.docUri?.toString().orEmpty()
        val now = System.currentTimeMillis()
        // 同一批结果短时间内不重复解码
        if (key == lastKey && now - lastAt < THROTTLE_MS) return
        lastKey = key
        lastAt = now

        withContext(Dispatchers.IO) {
            items.take(COUNT).forEach { item ->
                runCatching {
                    val req = ImageRequest.Builder(context)
                        .data(item.docUri)
                        .size(PX)
                        .memoryCacheKey(item.docUri.toString())
                        .diskCacheKey(item.docUri.toString())
                        .build()
                    // enqueue 只解码进缓存，不绑定到任何 View
                    loader.enqueue(req)
                }
            }
        }
    }

    fun invalidate() {
        lastKey = ""
        lastAt = 0L
    }

    fun uriOf(item: ImageItem): Uri = item.docUri
}
