package io.github.gcbond.openhc.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.diagnostics.DiagnosticsExport
import io.github.gcbond.openhc.diagnostics.LogExport

/**
 * 设置页「诊断」一组：
 * - 「导出」：一键存到固定位置（[DiagnosticsExport]）；
 * - 「另存到指定目录」：车主自己挑地方（插了 U 盘、或者车机的「下载」里看不到文件时用，[LogExport.launchSave]）；
 * - 「分享」：直接发给别人（[LogExport.share]）。
 *
 * 导出要读写几 MB 文件，都放在后台线程上做，完成后在行下面显示存到了哪里（或者为什么没存成）。
 */
@Composable
internal fun DiagnosticsSettingsSection() {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    /** 在后台线程跑 [work]，回到界面线程交结果。 */
    fun background(work: () -> Pair<String, Boolean>) {
        busy = true
        note = null
        val main = Handler(Looper.getMainLooper())
        Thread({
            val result = work()
            main.post {
                busy = false
                note = result
            }
        }, "OpenHC-DiagExport").start()
    }

    // 系统的「另存为」界面：结果回来时把报告写进用户挑的位置。
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            busy = false
            note = context.getString(R.string.log_export_cancelled) to false
            return@rememberLauncherForActivityResult
        }
        val app = context.applicationContext
        background { LogExport.finishSave(app, uri) to false }
    }

    SettingsGroup(stringResource(R.string.settings_group_diagnostics)) {
        GroupRow(spacing = 8.dp) {
            RowHeader(stringResource(R.string.settings_diag_export), stringResource(R.string.settings_diag_export_hint))
            ActionButton(
                text = stringResource(if (busy) R.string.settings_diag_exporting else R.string.settings_diag_export_button),
                enabled = !busy,
                onClick = {
                    val app = context.applicationContext
                    background {
                        when (val result = DiagnosticsExport.export(app)) {
                            is DiagnosticsExport.Result.Saved ->
                                app.getString(R.string.settings_diag_saved, result.location) to false
                            is DiagnosticsExport.Result.Failed ->
                                app.getString(R.string.settings_diag_failed, result.reason) to true
                        }
                    }
                },
            )
            note?.let { (text, warn) -> RowNote(text, warn) }
        }

        GroupRow(spacing = 8.dp) {
            RowHeader(stringResource(R.string.settings_diag_save_as), stringResource(R.string.settings_diag_save_as_hint))
            ActionButton(
                text = stringResource(R.string.settings_diag_save_as),
                enabled = !busy,
                onClick = {
                    // 安卓 4.4 起有系统的「另存为」界面，让用户自己挑目录；更早的版本没有，直接写 /sdcard/OpenHC/。
                    if (LogExport.canSaveToChosenFolder()) {
                        val intent = LogExport.saveIntent()
                        if (intent != null) {
                            note = null
                            saveAs.launch(intent)
                            return@ActionButton
                        }
                    }
                    background { LogExport.saveToLegacyDir(context.applicationContext) to false }
                },
            )
        }

        GroupRow(spacing = 8.dp) {
            RowHeader(stringResource(R.string.settings_diag_share), stringResource(R.string.settings_diag_share_hint))
            ActionButton(
                text = stringResource(R.string.settings_diag_share),
                enabled = !busy,
                onClick = {
                    val app = context.applicationContext
                    background {
                        if (LogExport.share(app)) {
                            app.getString(R.string.settings_diag_share) to false
                        } else {
                            app.getString(R.string.log_share_failed) to true
                        }
                    }
                },
            )
        }
    }
}
