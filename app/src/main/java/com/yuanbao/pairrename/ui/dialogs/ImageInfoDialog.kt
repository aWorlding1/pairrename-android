package com.yuanbao.pairrename.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.R
import com.yuanbao.pairrename.data.ExifMeta
import com.yuanbao.pairrename.model.ImageItem
import com.yuanbao.pairrename.util.Naming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单张图片的详细信息（含完整 EXIF）。
 *
 * 用途：判断两张图是不是同一张原图时，拍摄参数比缩略图可靠得多 ——
 * 两台不同相机、或 ISO/光圈明显不同的图，不可能还是同一张。
 *
 * EXIF 在这里**现场读取**（后台线程），不预先给所有图片都读一遍 ——
 * 那会拖慢扫描。只在这一张需要展示时才读。
 */
@Composable
fun ImageInfoDialog(
    item: ImageItem,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var info by remember(item.key) { mutableStateOf<ExifMeta.Info?>(null) }
    var loading by remember(item.key) { mutableStateOf(true) }

    LaunchedEffect(item.key) {
        withContext(Dispatchers.IO) {
            info = ExifMeta.read(ctx, item)
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                item.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Section("文件") {
                    KV("名称", item.displayName)
                    KV("大小", Naming.formatSize(item.size))
                    if (item.width > 0) {
                        KV("尺寸", "${item.width} × ${item.height}")
                    }
                    if (!item.path.isNullOrBlank()) {
                        KV("路径", item.path ?: "")
                    }
                    if (!item.canRename) {
                        Text(
                            text = "此存储不支持改名",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Section("拍摄信息") {
                    when {
                        loading -> Text(
                            "读取中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        info == null -> Text(
                            "没有读到 EXIF。可能是截图、网络图片，或被工具抹掉了元信息。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        else -> {
                            val i = info ?: return@Section
                            if (i.hasTime) {
                                KV("拍摄时间", ExifMeta.timestampName(i.takenAt))
                            }
                            if (i.camera.isNotBlank()) KV("相机", i.camera)
                            if (i.lens.isNotBlank()) KV("镜头", i.lens)
                            if (i.focal.isNotBlank()) KV("焦距", i.focal)
                            if (i.aperture.isNotBlank()) KV("光圈", "F${i.aperture}")
                            if (i.shutter.isNotBlank()) KV("快门", i.shutter)
                            if (i.iso > 0) KV("ISO", i.iso.toString())
                            if (i.software.isNotBlank()) KV("软件", i.software)
                            if (i.hasGps) {
                                KV("位置", "%.5f, %.5f".format(i.lat, i.lon))
                            }
                            if (i.exifWidth > 0) {
                                KV("EXIF 尺寸", "${i.exifWidth} × ${i.exifHeight}")
                            }
                            if (!i.hasTime && i.camera.isBlank() && !i.hasParams) {
                                Text(
                                    "这张图几乎没带元信息。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.padding(top = 6.dp)) { content() }
    }
}

@Composable
private fun KV(key: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(0.32f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.68f),
        )
    }
}
