package com.yuanbao.pairrename

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * 全局配置 Coil。
 *
 * 不配的话，Coil 会用默认 ImageLoader：不保证启用硬件位图，
 * 每张缩略图都要在 Java 堆里分配一份完整 ARGB_8888 位图。
 * 几十张图同时显示时 GC 压力极大，是列表卡顿的一大来源。
 * 这里显式打开硬件位图并给定缓存上限。
 */
class PairRenameApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // 硬件位图不占 Java 堆，能显著降低 GC 频率。
            // 注意不要开 allowRgb565：部分设备上会让缩略图偏色或加载异常。
            .allowHardware(true)
            .memoryCache {
                MemoryCache.Builder(this)
                    // 屏幕可用内存的 25%，与官方建议一致
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
}
