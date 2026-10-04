package com.yuanbao.pairrename

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.yuanbao.pairrename.model.Side
import com.yuanbao.pairrename.ui.PairRenameApp
import com.yuanbao.pairrename.vm.MainViewModel

class MainActivity : ComponentActivity() {

    /** 保存历史 CSV / 读取对照表，都由 Activity 持有回调。 */
    private lateinit var exportCsv: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var importCsv: androidx.activity.result.ActivityResultLauncher<Array<String>>
    private var pendingCsv: String = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val vm: MainViewModel by viewModels()

        exportCsv = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            val text = pendingCsv
            if (text.isEmpty()) return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                }
            }
            pendingCsv = ""
        }
        importCsv = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?.toString(Charsets.UTF_8)
            }.getOrNull() ?: return@registerForActivityResult
            vm.restoreFromCsv(text)
        }

        val pickLeft = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { vm.onFolderPicked(Side.LEFT, it) }
        }
        val pickRight = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { vm.onFolderPicked(Side.RIGHT, it) }
        }

        // 「管理所有文件」只能由用户在系统设置页手动开启
        val grantAllFiles: (() -> Unit)? =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                {
                    runCatching {
                        startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.parse("package:$packageName"),
                            ),
                        )
                    }
                }
            } else {
                null
            }

        setContent {
            PairRenameApp(
                vm = vm,
                onPickLeft = { pickLeft.launch(null) },
                onPickRight = { pickRight.launch(null) },
                onSaveCsv = { csv ->
                    pendingCsv = csv
                    exportCsv.launch("改名对照表.csv")
                },
                onOpenCsv = { importCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) },
                allFilesGranted = { com.yuanbao.pairrename.data.MediaStoreMeta.hasAllFilesAccess() },
                onGrantAllFiles = grantAllFiles,
            )
        }
    }
}
