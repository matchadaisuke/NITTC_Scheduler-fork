package jp.linkserver.nittcsc.ui

import jp.linkserver.nittcsc.sync.CloudSyncState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jp.linkserver.nittcsc.R
import jp.linkserver.nittcsc.InternalFeatureFlags
import jp.linkserver.nittcsc.ui.components.AppIconButton

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MainSharingActions(
    showSyncButton: Boolean,
    onOpenSync: () -> Unit,
    onOpenQrShare: () -> Unit,
    onOpenSettings: () -> Unit,
    // フォーク拡張: タップで今すぐ同期、長押しで同期設定。同期中は回転表示、失敗は赤で示す。
    syncState: CloudSyncState = CloudSyncState(),
    onSyncTap: (() -> Unit)? = null
) {
    if (showSyncButton) {
        if (onSyncTap == null) {
            AppIconButton(onClick = onOpenSync) {
                Icon(
                    painter = painterResource(R.drawable.sync_desktop),
                    contentDescription = stringResource(R.string.cd_open_local_sync)
                )
            }
        } else {
            val syncingDescription = stringResource(R.string.cd_sync_in_progress)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .combinedClickable(onClick = onSyncTap, onLongClick = onOpenSync),
                contentAlignment = Alignment.Center
            ) {
                if (syncState.isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(22.dp)
                            .semantics { contentDescription = syncingDescription },
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.sync_desktop),
                        contentDescription = stringResource(R.string.cd_sync_now_long_press_settings),
                        tint = if (syncState.lastFailed) MaterialTheme.colorScheme.error else LocalContentColor.current
                    )
                }
            }
        }
    }
    if (InternalFeatureFlags.QR_SHARE_BETA) {
        AppIconButton(onClick = onOpenQrShare) {
            Icon(Icons.Filled.QrCode, contentDescription = stringResource(R.string.qr_title))
        }
    }
    AppIconButton(onClick = onOpenSettings) {
        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
    }
}

@Composable
internal fun MainActionsOverflowMenu(
    showAiImport: Boolean,
    showSyncButton: Boolean,
    onOpenAiImport: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenQrShare: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AppIconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.cd_more_actions)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (showAiImport) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cd_ai_import)) },
                    onClick = {
                        expanded = false
                        onOpenAiImport()
                    },
                    leadingIcon = {
                        Icon(imageVector = Icons.Filled.AutoFixHigh, contentDescription = null)
                    }
                )
            }
            if (showSyncButton) DropdownMenuItem(
                text = { Text(stringResource(R.string.sync_title_mega_sync)) },
                onClick = {
                    expanded = false
                    onOpenSync()
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.sync_desktop),
                        contentDescription = null
                    )
                }
            )
            if (InternalFeatureFlags.QR_SHARE_BETA) DropdownMenuItem(
                text = { Text(stringResource(R.string.qr_title)) },
                onClick = {
                    expanded = false
                    onOpenQrShare()
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.QrCode, contentDescription = null)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_title)) },
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Settings, contentDescription = null)
                }
            )
        }
    }
}

@Composable
internal fun TimetableTargetDropdown(
    options: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.getOrNull(selectedIndex).orEmpty()
    Box {
        TextButton(onClick = { expanded = true }) {
            TimetableTargetLabel(selectedLabel)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelectedIndexChange(index)
                    },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null
                            )
                        }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
internal fun TimetableTargetLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        modifier = modifier.widthIn(max = 140.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
}
