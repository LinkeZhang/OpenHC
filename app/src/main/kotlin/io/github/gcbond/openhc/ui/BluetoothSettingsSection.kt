package io.github.gcbond.openhc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.BluetoothAddressCandidate
import io.github.gcbond.openhc.connection.BluetoothAddressPolicy
import io.github.gcbond.openhc.connection.BluetoothSource
import io.github.gcbond.openhc.settings.AppSettings

/**
 * 「连接方式」组里的车机蓝牙地址：一行「车机蓝牙地址 [当前地址 ›]」，说明里写来源；点它在下面展开手动填写（保存、清除）。
 * 没取到地址时一进来就是展开的。
 */
@Composable
internal fun BluetoothAddressSection(
    settings: AppSettings,
    systemBluetoothAddress: String?,
    onReadCandidates: () -> List<BluetoothAddressCandidate>,
) {
    var savedManual by remember { mutableStateOf(settings.manualBluetoothAddress) }
    var manualText by remember { mutableStateOf(savedManual ?: "") }
    var systemAddress by remember { mutableStateOf(systemBluetoothAddress) }
    val current = BluetoothAddressPolicy.choose(systemAddress, savedManual)
    var editing by rememberSaveable { mutableStateOf(current.source == BluetoothSource.UNAVAILABLE) }
    // 点「自动读取」之后的结果：读到的全部候选值，以及是读到了还是没读到。
    var readResult by remember { mutableStateOf<ReadResult?>(null) }
    var candidates by remember { mutableStateOf<List<BluetoothAddressCandidate>>(emptyList()) }

    val address = current.address
    ValueRow(
        title = stringResource(R.string.settings_bt),
        summary = stringResource(R.string.settings_bt_summary),
        value = address ?: stringResource(R.string.settings_bt_value_missing),
        description = when (current.source) {
            BluetoothSource.SYSTEM_PROPERTY -> stringResource(R.string.settings_bt_current_property, address ?: "")
            BluetoothSource.MANUAL -> stringResource(R.string.settings_bt_current_manual, address ?: "")
            BluetoothSource.UNAVAILABLE -> stringResource(R.string.settings_bt_current_unavailable)
        } + "\n" + stringResource(R.string.settings_bt_hint),
        valueColor = if (address == null) Palette.Warn else Palette.Text,
        monospace = address != null,
    ) { editing = !editing }
    if (!editing) return

    GroupRow(spacing = 8.dp) {
        val normalized = BluetoothAddressPolicy.normalize(manualText)
        val invalid = manualText.isNotBlank() && normalized == null
        OutlinedTextField(
            value = manualText,
            onValueChange = { manualText = it },
            label = { Text(stringResource(R.string.settings_bt_manual_label)) },
            isError = invalid,
            supportingText = if (invalid) {
                { Text(stringResource(R.string.settings_bt_invalid)) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(
                stringResource(R.string.action_save),
                onClick = {
                    settings.manualBluetoothAddress = normalized
                    savedManual = normalized
                    manualText = normalized ?: ""
                },
                kind = ActionKind.PRIMARY,
                enabled = normalized != null && normalized != savedManual,
            )
            ActionButton(
                stringResource(R.string.action_clear),
                onClick = {
                    settings.manualBluetoothAddress = null
                    savedManual = null
                    manualText = ""
                },
                enabled = savedManual != null,
            )
        }
        // 从系统读一次：把读到的所有候选值都列出来，车主对着「关于本机」挑正确的那个。
        // 只给一个值是不够的——厂商属性里可能存着别的设备的地址，猜错时反而更难查。
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(
                stringResource(R.string.action_read_bt_address),
                onClick = {
                    val found = onReadCandidates()
                    candidates = found
                    val usable = found.filter { it.usable }
                    systemAddress = usable.firstOrNull()?.address
                    readResult = if (found.isEmpty()) {
                        ReadResult.NotFound
                    } else {
                        // 只有一个可用值时直接填进去，省一次点击；多个时让车主自己挑。
                        if (usable.size == 1) manualText = usable.first().address
                        ReadResult.Found
                    }
                },
            )
        }
        when (readResult) {
            ReadResult.NotFound -> Text(
                stringResource(R.string.settings_bt_read_not_found),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Warn,
            )
            ReadResult.Found -> {
                Text(
                    stringResource(R.string.settings_bt_read_verify),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Warn,
                )
                for (candidate in candidates) {
                    val selected = candidate.usable && BluetoothAddressPolicy.normalize(manualText) == candidate.address
                    val label = when {
                        !candidate.usable -> R.string.settings_bt_candidate_unusable
                        selected -> R.string.settings_bt_candidate_selected
                        else -> R.string.settings_bt_candidate
                    }
                    Text(
                        stringResource(label, candidate.address, candidate.source),
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            !candidate.usable -> Palette.Muted
                            selected -> Palette.On
                            else -> Palette.Text
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            // 不可用的值（占位值、格式不对）只展示不填，免得填进去又被判无效。
                            .clickable(enabled = candidate.usable) { manualText = candidate.address }
                            .padding(vertical = 4.dp),
                    )
                }
            }
            null -> Unit
        }
    }
}

/** 点「自动读取」的结果。 */
private sealed class ReadResult {
    object Found : ReadResult()
    object NotFound : ReadResult()
}
