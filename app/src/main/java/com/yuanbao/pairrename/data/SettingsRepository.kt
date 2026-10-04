package com.yuanbao.pairrename.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yuanbao.pairrename.model.AppSettings
import com.yuanbao.pairrename.model.CardSize
import com.yuanbao.pairrename.model.ConflictPolicy
import com.yuanbao.pairrename.model.ExtensionPolicy
import com.yuanbao.pairrename.model.NumberingStyle
import com.yuanbao.pairrename.model.SameFolderMode
import com.yuanbao.pairrename.model.Side
import com.yuanbao.pairrename.model.SortOrder
import com.yuanbao.pairrename.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pairrename")

class SettingsRepository(private val context: Context) {

    private object K {
        val CONFLICT = stringPreferencesKey("conflict")
        val NUMBERING = stringPreferencesKey("numbering")
        val EXTENSION = stringPreferencesKey("extension")
        val SAME_FOLDER = stringPreferencesKey("same_folder")
        val SORT = stringPreferencesKey("sort")
        val RECURSIVE = booleanPreferencesKey("recursive")
        val AUTO_EXIF = booleanPreferencesKey("auto_exif")
        val SHOW_PERF = booleanPreferencesKey("show_perf")
        val THEME = stringPreferencesKey("theme")
        val CARD_SIZE = stringPreferencesKey("card_size")
        val PREFIX = stringPreferencesKey("prefix")
        val SUFFIX = stringPreferencesKey("suffix")
        val CONFIRM = booleanPreferencesKey("confirm")
        val CONFIRM_BATCH = booleanPreferencesKey("confirm_batch")
        val SHOW_META = booleanPreferencesKey("show_meta")
        val READ_BOUNDS = booleanPreferencesKey("read_bounds")
        val DIGITS = intPreferencesKey("digits")
        val START_INDEX = intPreferencesKey("start_index")
        val LEFT_TREE = stringPreferencesKey("left_tree")
        val RIGHT_TREE = stringPreferencesKey("right_tree")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p -> p.toSettings() }

    suspend fun save(s: AppSettings) {
        context.dataStore.edit { p ->
            p[K.CONFLICT] = s.conflictPolicy.name
            p[K.NUMBERING] = s.numbering.name
            p[K.EXTENSION] = s.extensionPolicy.name
            p[K.SAME_FOLDER] = s.sameFolderMode.name
            p[K.SORT] = s.sortOrder.name
            p[K.RECURSIVE] = s.recursive
            s.autoExif?.let { p[K.AUTO_EXIF] = it }
            p[K.SHOW_PERF] = s.showPerf
            p[K.THEME] = s.themeMode.name
            p[K.CARD_SIZE] = s.cardSize.name
            p[K.PREFIX] = s.prefix
            p[K.SUFFIX] = s.suffix
            p[K.CONFIRM] = s.confirmBeforeApply
            p[K.CONFIRM_BATCH] = s.confirmBatch
            p[K.SHOW_META] = s.showMeta
            p[K.READ_BOUNDS] = s.readBounds
            p[K.DIGITS] = s.digits
            p[K.START_INDEX] = s.startIndex
        }
    }

    suspend fun saveTree(side: Side, uri: String?) {
        context.dataStore.edit { p ->
            val key = if (side == Side.LEFT) K.LEFT_TREE else K.RIGHT_TREE
            if (uri == null) p.remove(key) else p[key] = uri
        }
    }

    suspend fun readTree(side: Side): String? {
        val key = if (side == Side.LEFT) K.LEFT_TREE else K.RIGHT_TREE
        return context.dataStore.data.catch { emit(emptyPreferences()) }.first()[key]
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        conflictPolicy = enumOr<ConflictPolicy>(this[K.CONFLICT]) ?: ConflictPolicy.AUTO_RENAME,
        numbering = enumOr<NumberingStyle>(this[K.NUMBERING]) ?: NumberingStyle.PARENTHESES,
        extensionPolicy = enumOr<ExtensionPolicy>(this[K.EXTENSION]) ?: ExtensionPolicy.KEEP_TARGET,
        sameFolderMode = enumOr<SameFolderMode>(this[K.SAME_FOLDER]) ?: SameFolderMode.SWAP,
        sortOrder = enumOr<SortOrder>(this[K.SORT]) ?: SortOrder.NAME,
        recursive = this[K.RECURSIVE] ?: false,
        autoExif = this[K.AUTO_EXIF],
        showPerf = this[K.SHOW_PERF] ?: false,
        themeMode = enumOr<ThemeMode>(this[K.THEME]) ?: ThemeMode.SYSTEM,
        cardSize = enumOr<CardSize>(this[K.CARD_SIZE]) ?: CardSize.MEDIUM,
        prefix = this[K.PREFIX].orEmpty(),
        suffix = this[K.SUFFIX].orEmpty(),
        confirmBeforeApply = this[K.CONFIRM] ?: false,
        confirmBatch = this[K.CONFIRM_BATCH] ?: false,
        showMeta = this[K.SHOW_META] ?: true,
        readBounds = this[K.READ_BOUNDS] ?: true,
        digits = (this[K.DIGITS] ?: 3).coerceIn(1, 8),
        startIndex = (this[K.START_INDEX] ?: 1).coerceAtLeast(0),
    )

    private inline fun <reified T : Enum<T>> enumOr(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
}
