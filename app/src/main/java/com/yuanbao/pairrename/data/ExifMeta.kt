package com.yuanbao.pairrename.data

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.roundToInt
import com.yuanbao.pairrename.model.ImageItem
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 读取 EXIF 信息，主要是**拍摄时间**。
 *
 * 为什么这是杀手级能力：相册导出、微信/QQ 传输、网盘下载之后，
 * 文件名会变成 `mmexport1a2b3c.jpg` 这种毫无意义的东西，
 * 序号和顺序全废 —— 但 EXIF 里的拍摄时间**不会被改掉**。
 * 于是「按拍摄时间配对」成了唯一还能把两套图对上的锚点。
 *
 * 有「管理所有文件」权限时走 File 直读（快）；
 * 没有时退化为通过 SAF Uri 读流（能用，但每张一次 IPC）。
 */
object ExifMeta {

    /**
     * 一张图的元信息。
     *
     * 除了拍摄时间，还记录拍摄参数 —— 判断「是不是同一张原图」时，
     * 两台不同相机、或参数明显不同的图，不可能还是同一张。
     * 这比单纯比对尺寸可靠得多。
     *
     * 所有字段都可能取不到（空串 / 0），UI 必须能容忍缺失，
     * 不能因为某个字段为空就显示成「未知未知未知」。
     */
    data class Info(
        /** 拍摄时间（毫秒），取不到为 0。 */
        val takenAt: Long = 0,
        val camera: String = "",
        /** 拍摄时的旋转角度，0/90/180/270。 */
        val orientation: Int = 0,
        /** 镜头型号。 */
        val lens: String = "",
        /** ISO，0 表示未记录。 */
        val iso: Int = 0,
        /** 光圈（F 值），如 "1.8"。 */
        val aperture: String = "",
        /** 快门，如 "1/125"。 */
        val shutter: String = "",
        /** 焦距，如 "26mm"。 */
        val focal: String = "",
        /** 处理软件，如 "Adobe Photoshop"。用于判断是否被后期过。 */
        val software: String = "",
        /** EXIF 里记录的像素宽/高（可能与文件实际尺寸不同）。 */
        val exifWidth: Int = 0,
        val exifHeight: Int = 0,
        /** 纬度/经度，取不到为 null。 */
        val lat: Double? = null,
        val lon: Double? = null,
    ) {
        val hasTime: Boolean get() = takenAt > 0
        val hasGps: Boolean get() = lat != null && lon != null

        /**
         * 有没有任何"值得展示"的拍摄参数。
         * 全都没有时 UI 应该直接说「没有 EXIF 参数」，
         * 而不是列一堆空行。
         */
        val hasParams: Boolean
            get() = lens.isNotBlank() || iso > 0 || aperture.isNotBlank() ||
                shutter.isNotBlank() || focal.isNotBlank() || software.isNotBlank()
    }

    private val PARSERS = listOf(
        "yyyy:MM:dd HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy:MM:dd",
    )

    /**
     * EXIF 2.2 的 ISO 标签名。
     *
     * androidx 把常量改名成 TAG_PHOTOGRAPHIC_SENSITIVITY 后，旧的
     * TAG_ISO_SPEED_RATINGS 就被标了废弃 —— 但**标签字符串本身没变**，
     * 老照片仍然只写这一个。用字面量既能读到，又不引入废弃警告。
     */
    private const val LEGACY_TAG_ISO = "ISOSpeedRatings"

    /** 通过流读取（无需权限，走 SAF）。 */
    fun fromStream(context: Context, uri: Uri): Info? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { s ->
            parse(ExifInterface(s))
        }
    }.getOrNull()

    /** 通过文件路径读取（有权限时快得多）。 */
    fun fromPath(path: String): Info? = runCatching {
        if (path.isBlank()) return null
        parse(ExifInterface(path))
    }.getOrNull()

    /** 有权限优先走路径，否则退回 SAF 流。 */
    fun read(context: Context, item: ImageItem): Info? {
        val p = item.path
        if (!p.isNullOrBlank()) {
            fromPath(p)?.let { if (it.hasTime) return it }
        }
        return fromStream(context, item.docUri)
    }

    private fun parse(exif: ExifInterface): Info {
        // 优先 DateTimeOriginal，其次 DateTimeDigitized，最后 DateTime
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        // 刻意**不用** OffsetTimeOriginal。
        //
        // 它记录的是拍摄地的 UTC 偏移（如 +09:00）。把 DateTimeOriginal 按它换算成
        // 绝对时刻，会把"我在东京上午 10 点拍的"显示成北京时间 9 点 ——
        // 而用户按拍摄时间找照片时，脑子里记的恰恰是当地那个时刻。
        // 所以一律按本地时间解释，这也让跨时区混拍的一批照片时间轴保持单调。
        //
        // （原来这里把 OFFSET_TIME_ORIGINAL 读出来传进 parseTime，
        //   而 parseTime 根本没接这个参数 —— 一个会让维护者以为"已经处理了时区"
        //   的死参数。索性删掉，并把理由写在这儿。）
        val millis = parseTime(raw) ?: parseGpsTime(exif)

        val make = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty().trim()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty().trim()
        val camera = when {
            model.startsWith(make, ignoreCase = true) -> model
            make.isBlank() -> model
            model.isBlank() -> make
            else -> "$make $model"
        }.trim()

        val orientation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

        // ISO：EXIF 2.3 起叫 PhotographicSensitivity，2.2 的老文件写的是
        // ISOSpeedRatings。后者在 androidx 里被标了废弃（只是改了名，
        // 底层标签字符串没变），但老照片还得靠它 ——
        // 直接用字面量取值，既保住兼容又不触发废弃警告。
        val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            ?.trimToIntOrNull()
            ?: exif.getAttribute(LEGACY_TAG_ISO)?.trimToIntOrNull()
            ?: 0

        // 快门常见两种写法：有理数 "1/125" 或小数 "0.008"
        val shutter = formatShutter(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))

        // 用返回 double[] 的无参版本。
        // 老的 FloatArray 重载已被废弃，而且 float 只有约 7 位有效数字 ——
        // 换算到经纬度上就是好几米的误差，两组坐标明明同一点却"不相等"。
        // （显式声明类型，不用 `null to null` 的三元写法 —— 那要靠类型推断
        //   去合 `Pair<Double,Double>` 和 `Pair<Nothing?,Nothing?>`，没必要赌。）
        val gps = exif.getLatLong()
        val lat: Double?
        val lon: Double?
        if (gps != null && gps.size >= 2) {
            lat = gps[0]
            lon = gps[1]
        } else {
            lat = null
            lon = null
        }

        return Info(
            takenAt = millis ?: 0L,
            camera = camera,
            orientation = orientation,
            lens = joinNonBlank(
                exif.getAttribute(ExifInterface.TAG_LENS_MAKE),
                exif.getAttribute(ExifInterface.TAG_LENS_MODEL),
            ),
            iso = iso,
            aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER).orEmpty().trim(),
            shutter = shutter,
            focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH).orEmpty()
                .trim().let { if (it.isBlank()) "" else "$it mm" },
            software = exif.getAttribute(ExifInterface.TAG_SOFTWARE).orEmpty().trim(),
            exifWidth = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
            exifHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
            lat = lat,
            lon = lon,
        )
    }

    /** "80" -> 80；"80 " -> 80；其他一律 null（不要因解析失败就崩）。 */
    private fun String.trimToIntOrNull(): Int? = trim().toIntOrNull()

    /** 拼接两段非空文本（镜头厂商 + 型号），避免拼出 "  " 这种尴尬结果。 */
    private fun joinNonBlank(a: String?, b: String?): String {
        val x = a.orEmpty().trim()
        val y = b.orEmpty().trim()
        return when {
            x.isBlank() -> y
            y.isBlank() -> x
            // 型号里已经含厂商名就不重复拼
            y.startsWith(x, ignoreCase = true) -> y
            else -> "$x $y"
        }
    }

    /**
     * 快门格式化：EXIF 里常存 "0.008"，用户想看的是 "1/125"。
     * 转换失败就原样返回 —— 显示原始值总比显示空白好。
     */
    private fun formatShutter(raw: String?): String {
        val v = raw?.trim()?.toDoubleOrNull() ?: return raw.orEmpty().trim()
        if (v <= 0) return ""
        return if (v >= 1) {
            // 1 秒以上保留一位小数，如 "1.3s"
            "%.1fs".format(v)
        } else {
            "1/%d".format((1.0 / v).roundToInt().coerceAtLeast(1))
        }
    }

    /**
     * EXIF 时间字符串 → 毫秒。
     *
     * 刻意保持简单：这段代码无法在沙盒里编译验证，
     * 所以宁可少支持几种边缘格式，也不要写脆弱的解析逻辑。
     * 标准 EXIF 格式是 `2024:03:15 14:30:22`，直接按本地时区解释即可 ——
     * 相机存的就是拍摄时的本地时间，这样反而与用户直觉一致。
     */
    private fun parseTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        for (pattern in PARSERS) {
            val millis = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(trimmed)?.time
            }.getOrNull()
            if (millis != null) return millis
        }
        // 兜底：部分设备写的是 ISO 8601
        return runCatching {
            java.time.LocalDateTime.parse(trimmed.replace(' ', 'T')).let {
                it.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }.getOrNull()
    }

    /**
     * EXIF 里的有理数（RATIONAL）→ Double。
     *
     * 标准写法是 `分子/分母`，比如 `14/1`；也有设备直接写 `14`。
     * 秒还可能是 `2250/100` 这种非整除的，所以按浮点算而不是取分子。
     *
     * 分母为 0 视为无效 —— 有些手机会写出这种垃圾值，
     * 直接除会得到 Infinity 再 toInt() 就崩了。
     */
    private fun rational(raw: String): Double? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val slash = t.indexOf('/')
        if (slash < 0) return t.toDoubleOrNull()
        val num = t.substring(0, slash).trim().toDoubleOrNull() ?: return null
        val den = t.substring(slash + 1).trim().toDoubleOrNull() ?: return null
        if (den == 0.0) return null
        return num / den
    }

    /**
     * GPS 时间兜底。
     * 只在完全没有 DateTime 时才用，且失败就放弃 —— 不值得为它冒险。
     *
     * 注意 GPSTimeStamp 的格式：标准是 `14/1,30/1,22/1` 三个 RATIONAL。
     * 早先这里对时分直接 `toInt()`，遇到 `14/1` 会抛 NumberFormatException，
     * 被 runCatching 一吞就成了 null —— 也就是说**这个兜底从来没生效过**。
     * 现在三个分量统一走 [rational]。
     */
    private fun parseGpsTime(exif: ExifInterface): Long? {
        val date = exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP) ?: return null
        val time = exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP) ?: return null
        return runCatching {
            val parts = time.split(",")
            if (parts.size != 3) return null
            val h = rational(parts[0])?.toInt() ?: return null
            val m = rational(parts[1])?.toInt() ?: return null
            val s = rational(parts[2])?.toInt() ?: return null
            val day = date.split(":").map { it.trim().toIntOrNull() }
            val y = day.getOrNull(0) ?: return null
            val mo = day.getOrNull(1) ?: return null
            val d = day.getOrNull(2) ?: return null
            java.time.ZonedDateTime.of(y, mo, d, h, m, s, 0, java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** 生成「按拍摄时间」的文件名，如 20240315_143022。 */
    fun timestampName(millis: Long): String {
        if (millis <= 0) return ""
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date(millis))
    }

    /**
     * 把 EXIF 方向转成 Compose 的旋转角度（顺时针）。
     *
     * 为什么需要：很多手机照片是「横着存 + 用 EXIF 标记旋转 90°」的，
     * 不修正的话在应用里看起来就是躺倒的 —— 而判断两张图是不是同一张时，
     * 一张躺倒一张正立会让人以为是两张不同的图。
     */
    fun rotationDegrees(orientation: Int): Float = when (orientation) {
        90 -> 90f
        180 -> 180f
        270 -> 270f
        else -> 0f
    }
}
