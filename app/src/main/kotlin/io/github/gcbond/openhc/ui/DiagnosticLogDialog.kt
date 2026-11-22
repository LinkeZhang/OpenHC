package io.github.gcbond.openhc.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.settings.DiagnosticLog

/**
 * 连接日志：在车机屏幕上直接看，也能「复制」或「分享」出去。
 *
 * 车机上普通应用读不到 logcat，所以这份日志是应用自己写的文件；用户把内容复制出来发过来就行，
 * 不需要 adb、不需要 U 盘。
 */
@Composable
internal fun DiagnosticLogDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val compact = LocalCompact.current
    var text by remember { mutableStateOf(DiagnosticLog.read()) }
    var notice by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(dismissOnClickOutside = false)) {
        Column(
            Modifier
                .widthIn(max = 900.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.CardRadius))
                .background(Palette.Raised)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.log_title), style = UiType.status, color = Palette.Text)
            Text(
                stringResource(R.string.log_hint),
                style = UiType.rowTitle(compact).copy(lineHeight = 1.5.em, fontWeight = FontWeight.Normal),
                color = Palette.Muted,
            )
            Text(
                text,
                style = UiType.rowTitle(compact).copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 1.4.em,
                    fontWeight = FontWeight.Normal,
                ),
                color = Palette.Text,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            )
            notice?.let {
                Text(it, style = UiType.rowTitle(compact), color = Palette.On)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(stringResource(R.string.log_refresh), onClick = {
                    text = DiagnosticLog.read()
                    notice = null
                })
                ActionButton(stringResource(R.string.log_clear), onClick = {
                    DiagnosticLog.clear()
                    text = DiagnosticLog.read()
                    notice = context.getString(R.string.log_cleared)
                })
                ActionButton(stringResource(R.string.log_copy), onClick = {
                    clipboard.setText(AnnotatedString(text))
                    notice = context.getString(R.string.log_copied)
                })
                ActionButton(stringResource(R.string.log_share), onClick = {
                    // 分享走系统选择器：车机上有微信/邮箱就能直接发出去。
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_title))
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    try {
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.log_share)))
                    } catch (e: RuntimeException) {
                        notice = context.getString(R.string.log_share_failed)
                    }
                }, kind = ActionKind.PRIMARY)
                ActionButton(stringResource(R.string.action_back), onClick = onClose)
            }
        }
    }
}
