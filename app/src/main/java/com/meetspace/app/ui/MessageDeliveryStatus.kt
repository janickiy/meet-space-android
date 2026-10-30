package com.meetspace.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Delivery errors belong to their message, without a toast, banner or live announcement. */
@Composable
internal fun MessageDeliveryStatus(
    requestId: String,
    failed: Boolean,
    sending: Boolean,
    retry: () -> Unit,
    discard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember(requestId) { mutableStateOf(false) }
    if (sending) CircularProgressIndicator(modifier.size(14.dp).testTag("message-sending-$requestId"),
        strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.primary)
    else if (failed) {
        IconButton(onClick = { menu = true }, modifier = modifier.size(48.dp).testTag("message-failed-$requestId")) {
            Icon(Icons.Outlined.ErrorOutline, "Сообщение не отправлено. Действия", Modifier.size(21.dp), tint = Color(0xFFD93042))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Повторить отправку") },
                    modifier = Modifier.testTag("message-retry-$requestId"), onClick = { menu = false; retry() })
                DropdownMenuItem(text = { Text("Удалить с устройства") },
                    modifier = Modifier.testTag("message-discard-$requestId"), onClick = { menu = false; discard() })
            }
        }
    }
}
