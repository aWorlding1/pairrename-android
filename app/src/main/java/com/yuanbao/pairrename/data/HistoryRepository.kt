package com.yuanbao.pairrename.data

import android.content.Context
import android.net.Uri
import com.yuanbao.pairrename.model.RenameStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一次已完成的改名操作（落盘单元）。
 *
 * @param id        单调递增，用于排序与去重
 * @param time      时间戳
 * @param label     人类可读的摘要
 * @param steps     改名步骤，含新旧名字与文档 Uri
 */
data class HistoryEntry(
    val id: Long,
    val time: Long,
    val label: String,
    val steps: List<RenameStep>,
)

/**
 * 改名历史的持久化与导入导出。
 *
 * 为什么必须有它：撤销栈在内存里，App 一关、进程一被回收就全没了。
 * 批量改完 500 个文件名，隔天发现配对错了 20 个 —— 没有落盘的历史就彻底没救。
 * 这里把每次操作的「旧名 → 新名」写进应用私有目录，并支持导出成 CSV
 * 存到用户自己的盘里，需要时再读回来整体还原。
 *
 * 格式用 JSONL（每行一条），追加写、崩溃也不太会破坏已有记录。
 */
class HistoryRepository(private val context: Context) {

    companion object {
        private const val FILE = "rename_history.jsonl"
        /** 最多保留多少条操作记录，防止无限增长。 */
        private const val MAX_ENTRIES = 200
        /** 单条最多记多少步，防止超大批量把文件撑爆。 */
        private const val MAX_STEPS = 5000
    }

    private val file: File get() = File(context.filesDir, FILE)
    private val lock = Mutex()

    suspend fun append(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                val line = JSONObject().apply {
                    put("id", entry.id)
                    put("time", entry.time)
                    put("label", entry.label)
                    put("steps", JSONArray().apply {
                        entry.steps.take(MAX_STEPS).forEach { s ->
                            put(JSONObject().apply {
                                put("uri", s.docUri.toString())
                                put("old", s.previousName)
                                put("new", s.newName)
                                put("side", s.side.name)
                            })
                        }
                    })
                }.toString()
                file.appendText(line + "\n")
                trimIfNeeded()
            }
        }
    }

    suspend fun load(): List<HistoryEntry> = withContext(Dispatchers.IO) {
        lock.withLock { readAll() }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock { runCatching { file.writeText("") } }
    }

    private fun readAll(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<HistoryEntry>()
        file.useLines { lines ->
            lines.forEach { raw ->
                if (raw.isBlank()) return@forEach
                runCatching {
                    val o = JSONObject(raw)
                    val arr = o.optJSONArray("steps") ?: return@runCatching
                    val steps = ArrayList<RenameStep>(arr.length())
                    for (i in 0 until arr.length()) {
                        val s = arr.getJSONObject(i)
                        val uri = Uri.parse(s.getString("uri"))
                        val side = runCatching {
                            com.yuanbao.pairrename.model.Side.valueOf(s.optString("side", "LEFT"))
                        }.getOrDefault(com.yuanbao.pairrename.model.Side.LEFT)
                        steps += RenameStep(
                            docUri = uri,
                            previousName = s.getString("old"),
                            newName = s.getString("new"),
                            side = side,
                        )
                    }
                    out += HistoryEntry(
                        id = o.optLong("id", 0),
                        time = o.optLong("time", 0),
                        label = o.optString("label", ""),
                        steps = steps,
                    )
                }
            }
        }
        return out.sortedByDescending { it.id }
    }

    /** 超出上限就截断文件头部，只保留最新的记录。 */
    private fun trimIfNeeded() {
        runCatching {
            val all = readAll()
            if (all.size <= MAX_ENTRIES) return
            val keep = all.take(MAX_ENTRIES)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.bufferedWriter().use { w ->
                keep.forEach { e ->
                    w.write(JSONObject().apply {
                        put("id", e.id)
                        put("time", e.time)
                        put("label", e.label)
                        put("steps", JSONArray().apply {
                            e.steps.forEach { s ->
                                put(JSONObject().apply {
                                    put("uri", s.docUri.toString())
                                    put("old", s.previousName)
                                    put("new", s.newName)
                                    put("side", s.side.name)
                                })
                            }
                        })
                    }.toString())
                    w.newLine()
                }
            }
            if (tmp.exists() && tmp.length() > 0) {
                tmp.copyTo(file, overwrite = true)
            }
            tmp.delete()
        }
    }

    /**
     * 导出成 CSV（BOM 开头，Excel 直接打开不乱码）。
     * 用户拿它当存档；需要时再用 [parseCsv] 读回来整体还原。
     */
    fun toCsv(entries: List<HistoryEntry>): String {
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append("序号,时间,操作,原文件名,新文件名\n")
        var i = 0
        entries.asReversed().forEach { e ->
            e.steps.forEach { s ->
                i++
                sb.append(i).append(',')
                sb.append(quote(formatTime(e.time))).append(',')
                sb.append(quote(e.label)).append(',')
                sb.append(quote(s.previousName)).append(',')
                sb.append(quote(s.newName)).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * 从导出的 CSV 反向解析出「新名 → 旧名」映射，用于整体还原。
     * 只认有 5 列且表头匹配的行，容错但不放松。
     */
    fun parseCsv(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val rows = text.replace("\uFEFF", "").lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        if (rows.isEmpty()) return out
        val header = rows.first().split(",").map { it.trim() }
        val expected = listOf("序号", "时间", "操作", "原文件名", "新文件名")
        if (header != expected) return out
        rows.drop(1).forEach { raw ->
            val cols = splitCsvLine(raw)
            if (cols.size < 5) return@forEach
            val old = cols[3]
            val new = cols[4]
            if (old.isNotBlank() && new.isNotBlank()) out += new to old
        }
        return out
    }

    /** 极简 CSV 行解析，支持双引号包裹与转义。 */
    private fun splitCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    private fun quote(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }

    private val sdf by lazy {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    }

    private fun formatTime(millis: Long): String =
        if (millis <= 0) "" else sdf.format(java.util.Date(millis))
}
