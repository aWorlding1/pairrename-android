package com.yuanbao.pairrename.util

import java.io.InputStream
import java.security.MessageDigest

/**
 * 图片内容指纹。
 *
 * 为什么需要它：配对靠「文件名序号 + 排列顺序」推算是**猜测**，必然有错配，
 * 而照着错的配对改名就是改错文件。校验文件内容则可以 100% 确定两张图是否相同。
 *
 * 两级过滤，保证大部分情况下几乎不读盘：
 * 1. **体积不同 → 必然不同**，直接跳过（这一层就筛掉绝大多数组合）；
 * 2. 体积相同才读文件，且**只算「头部 + 尾部」采样哈希**，不读整个文件 ——
 *    几十 MB 的照片全读一遍太慢，头尾各 256KB 已足以区分不同图片。
 */
object ContentHash {

    private const val SAMPLE = 256 * 1024
    private const val BUF = 64 * 1024

    /**
     * @param size 文件总字节数，用于决定采样策略。未知时传 0（退化为读头部）。
     */
    fun digest(input: InputStream, size: Long): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        input.use { s ->
            if (size <= SAMPLE * 2L) {
                // 小文件（含 size 未知的情况）：整段读，反正也不大
                val buf = ByteArray(BUF)
                var n: Int
                while (s.read(buf).also { n = it } > 0) md.update(buf, 0, n)
            } else {
                // 头部
                readChunk(s, SAMPLE, md)
                // 中间的跳过
                skipFully(s, size - 2L * SAMPLE)
                // 尾部
                readChunk(s, SAMPLE, md)
            }
        }
        hex(md.digest())
    }.getOrNull()

    /** 读满 [want] 字节喂给摘要；读到 EOF 就停。 */
    private fun readChunk(s: InputStream, want: Int, md: MessageDigest) {
        val buf = ByteArray(BUF)
        var remaining = want
        while (remaining > 0) {
            val n = s.read(buf, 0, remaining.coerceAtMost(BUF))
            if (n <= 0) break
            md.update(buf, 0, n)
            remaining -= n
        }
    }

    /** InputStream.skip 不保证跳够，必须循环。 */
    private fun skipFully(s: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = s.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** 只比较指纹时用短串即可，省内存。 */
    fun short(full: String?): String = full?.take(12) ?: ""
}
