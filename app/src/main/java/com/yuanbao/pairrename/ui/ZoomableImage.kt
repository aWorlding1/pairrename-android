package com.yuanbao.pairrename.ui

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.yuanbao.pairrename.data.ExifMeta
import com.yuanbao.pairrename.model.ImageItem

/**
 * 可缩放、可平移、并按 EXIF 方向修正显示的图片。
 *
 * 三个能力都是判断「两张图是不是同一张」时真正需要的：
 * 1. **缩放** —— 118dp 的缩略图看不清细节，而"是不是同一张"
 *    往往就取决于某个局部（脸上的痣、背景的招牌）
 * 2. **平移** —— 放大后必须能看到各个角落，否则等于没放大
 * 3. **EXIF 方向修正** —— 很多照片是横存竖显的，不修正会躺倒，
 *    一张躺倒一张正立会让人误判成两张不同的图
 *
 * 双击在 1x / 2.5x 之间切换；手势结束后若缩回 <=1x 会复位偏移，
 * 避免出现"图片跑到框外看着像没了"的错觉。
 */
@Composable
fun ZoomableImage(
    item: ImageItem,
    rotation: Float,
    modifier: Modifier = Modifier,
    maxSize: Int = 1200,
) {
    var scale by remember(item.key) { mutableFloatStateOf(1f) }
    var offset by remember(item.key) { mutableStateOf(Offset.Zero) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .pointerInput(item.key) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 6f)
                    scale = next
                    // 放大后才允许平移；1x 时平移会把图推出框外
                    offset = if (next > 1f) offset + pan else Offset.Zero
                }
            },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.docUri)
                // 按最长边限制解码尺寸：整张解码 4000×3000 会吃掉几十 MB
                .size(maxSize)
                .crossfade(true)
                .build(),
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                    rotationZ = rotation,
                ),
        )
    }
}

/** 从 EXIF 方向算出旋转角度（未读取时按 0 处理）。 */
fun rotationFor(info: ExifMeta.Info?): Float = ExifMeta.rotationDegrees(info?.orientation ?: 0)
