package com.yuanbao.pairrename.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.recentStore by preferencesDataStore("recent_folders")

/**
 * 最近用过的文件夹。
 *
 * 每次用都要通过系统文件选择器一层层点进去，是最烦的一步 ——
 * 尤其目录层级深的时候。这里记住最近用过的，一键切回。
 */
class RecentFolders(private val context: Context) {

    private object K {
        val LIST = stringPreferencesKey("list")
    }

    val folders: Flow<List<Pair<String, String>>> = context.recentStore.data.map { p ->
        parse(p[K.LIST].orEmpty())
    }

    suspend fun touch(uri: Uri, label: String) {
        val key = uri.toString()
        context.recentStore.edit { pref ->
            val cur = parse(pref[K.LIST].orEmpty()).toMutableList()
            cur.removeAll { it.second == key }
            cur.add(0, label to key)
            pref[K.LIST] = encode(cur.take(MAX))
        }
    }

    suspend fun remove(key: String) {
        context.recentStore.edit { pref ->
            pref[K.LIST] = encode(parse(pref[K.LIST].orEmpty()).filter { it.second != key })
        }
    }

    suspend fun clear() {
        context.recentStore.edit { pref -> pref[K.LIST] = "" }
    }

    private fun encode(list: List<Pair<String, String>>): String =
        list.joinToString("\u001E") { (label, uri) -> "$label\u001F$uri" }

    private fun parse(raw: String): List<Pair<String, String>> {
        if (raw.isBlank()) return emptyList()
        return raw.split("\u001E").mapNotNull { chunk ->
            val f = chunk.split("\u001F")
            if (f.size < 2) return@mapNotNull null
            val uri = f[1]
            // 校验还能不能解析成 Uri，坏了就丢掉
            runCatching { Uri.parse(uri) }.getOrNull() ?: return@mapNotNull null
            f[0] to uri
        }
    }

    private companion object {
        const val MAX = 12
    }
}
