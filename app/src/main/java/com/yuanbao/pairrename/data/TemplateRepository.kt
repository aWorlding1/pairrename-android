package com.yuanbao.pairrename.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yuanbao.pairrename.model.BatchMode
import com.yuanbao.pairrename.model.BatchParams
import com.yuanbao.pairrename.model.BatchTemplate
import com.yuanbao.pairrename.model.CaseOp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.templateStore by preferencesDataStore("batch_templates")

/**
 * 批量参数模板的持久化。
 *
 * 为什么需要：同样的改名规则（比如「统一加前缀 vacation_」）会反复用。
 * 每次都重填一遍参数很烦，存成模板下次一键套用。
 *
 * 用 JSON 字符串存，不引三方库 —— 多一个依赖就多一分编译失败的风险。
 * 解析失败时返回空列表而不是崩溃：模板丢了可以重建，崩了就什么都没了。
 */
class TemplateRepository(private val context: Context) {

    private object K {
        val LIST = stringPreferencesKey("templates")
    }

    val templates: Flow<List<BatchTemplate>> = context.templateStore.data.map { p ->
        parse(p[K.LIST].orEmpty())
    }

    suspend fun save(list: List<BatchTemplate>) {
        context.templateStore.edit { it[K.LIST] = encode(list) }
    }

    suspend fun add(t: BatchTemplate) {
        context.templateStore.edit { p ->
            val cur = parse(p[K.LIST].orEmpty()).toMutableList()
            cur.removeAll { it.name == t.name }
            cur.add(0, t)
            // 最多留 20 个，再多也用不上，还占地方
            p[K.LIST] = encode(cur.take(20))
        }
    }

    suspend fun remove(id: Long) {
        context.templateStore.edit { p ->
            p[K.LIST] = encode(parse(p[K.LIST].orEmpty()).filter { it.id != id })
        }
    }

    // ---------------- 编解码 ----------------

    /**
     * 极简编码：字段用 \u001F 分隔，模板用 \u001E 分隔。
     * 这两个字符不可能出现在正常文件名/参数里，足够安全。
     */
    private fun encode(list: List<BatchTemplate>): String =
        list.joinToString("\u001E") { t ->
            listOf(
                t.id.toString(),
                t.name,
                t.mode.name,
                t.params.baseName,
                t.params.startIndex.toString(),
                t.params.digits.toString(),
                t.params.find,
                t.params.replace,
                t.params.prefix,
                t.params.suffix,
                t.params.caseOp.name,
                if (t.params.keepExtension) "1" else "0",
            ).joinToString("\u001F")
        }

    private fun parse(raw: String): List<BatchTemplate> {
        if (raw.isBlank()) return emptyList()
        return raw.split("\u001E").mapNotNull { chunk ->
            val f = chunk.split("\u001F")
            // 字段数不对说明数据损坏（版本变化等），跳过而不是崩
            if (f.size < 12) return@mapNotNull null
            runCatching {
                BatchTemplate(
                    id = f[0].toLongOrNull() ?: 0L,
                    name = f[1],
                    mode = runCatching { BatchMode.valueOf(f[2]) }.getOrDefault(BatchMode.REPLACE),
                    params = BatchParams(
                        baseName = f[3],
                        startIndex = f[4].toIntOrNull() ?: 1,
                        digits = f[5].toIntOrNull() ?: 3,
                        find = f[6],
                        replace = f[7],
                        prefix = f[8],
                        suffix = f[9],
                        caseOp = runCatching { CaseOp.valueOf(f[10]) }.getOrDefault(CaseOp.LOWER),
                        keepExtension = f[11] == "1",
                    ),
                )
            }.getOrNull()
        }
    }
}
