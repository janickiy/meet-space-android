package com.meetspace.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.app.AvatarImage

/** Entry cards mirror av-profile-card and ge-photo-section in the design prototype. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AvatarPhotoCard(
    name: String,
    id: String?,
    version: String?,
    enabled: Boolean,
    busy: Boolean,
    pick: () -> Unit,
    remove: () -> Unit,
    modifier: Modifier = Modifier,
    group: Boolean = false,
    canManage: Boolean = true,
) {
    val prefix = if (group) "group" else "account"
    var confirmRemove by remember(id, version) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val hasPhoto = version != null
    BoxWithConstraints(modifier.fillMaxWidth().testTag("$prefix-avatar-card")) {
        val compact = maxWidth < 340.dp
        val previewSize = if (compact) 80.dp else 96.dp
        Surface(
            shape = RoundedCornerShape(if (group) 18.dp else if (compact) 17.dp else 20.dp),
            color = if (group) colors.background else colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Row(Modifier.fillMaxWidth().padding(if (compact) 15.dp else 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 15.dp else 18.dp)) {
                Box(Modifier.size(previewSize).testTag("$prefix-avatar-preview")
                    .clickable(enabled = enabled && canManage, role = Role.Button,
                        onClickLabel = if (hasPhoto) "Изменить фото" else "Добавить фото", onClick = pick)
                    .semantics { contentDescription = (if (hasPhoto) "Изменить" else "Добавить") + if (group) " фото группы" else " фото профиля" }) {
                    AvatarImage(name, id, version, group = group, size = previewSize,
                        shape = RoundedCornerShape(if (compact) 23.dp else 28.dp))
                    if (!group) Surface(
                        Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(32.dp),
                        shape = CircleShape, color = colors.primary, border = BorderStroke(3.dp, colors.surface),
                    ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.CameraAlt, null, Modifier.size(15.dp), tint = colors.onPrimary) } }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (group) "Фото группы" else "Фото профиля", fontSize = if (group) 14.sp else 15.sp,
                        lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    Text(if (group) "Отображается в списке чатов и переписке." else "Ваше фото видно участникам встреч и чатов.",
                        fontSize = if (group) 11.sp else 12.sp, lineHeight = if (group) 17.sp else 19.sp, color = colors.onSurfaceVariant)
                    if (canManage) {
                        FlowRow(Modifier.fillMaxWidth().padding(top = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            OutlinedButton(onClick = pick, enabled = enabled,
                                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 11.dp, vertical = 9.dp),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("$prefix-avatar-pick")) {
                                Icon(if (hasPhoto) Icons.Outlined.Edit else if (group) Icons.Outlined.CameraAlt else Icons.Outlined.Upload, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp)); Text(if (hasPhoto) "Изменить фото" else "Добавить фото", fontSize = 11.sp)
                            }
                            if (hasPhoto) TextButton(onClick = { confirmRemove = true }, enabled = enabled,
                                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
                                modifier = Modifier.heightIn(min = 48.dp).testTag(if (group) "group-avatar-delete" else "account-avatar-remove")) {
                                Text("Удалить", color = colors.error, fontSize = 11.sp)
                            }
                            if (busy) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(21.dp).testTag("$prefix-avatar-progress"), strokeWidth = 2.dp)
                            }
                        }
                    } else Text("Фото группы меняет администратор.", fontSize = 11.sp, lineHeight = 17.sp,
                        color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp).testTag("group-avatar-permission"))
                }
            }
        }
    }
    if (confirmRemove && hasPhoto && canManage) {
        AlertDialog(onDismissRequest = { if (!busy) confirmRemove = false },
            title = { Text("Удалить фото?") },
            text = { Text("Вместо фото ${if (group) "группы" else "профиля"} снова появятся инициалы. Вы сможете добавить новое изображение.") },
            confirmButton = { Button(onClick = { confirmRemove = false; remove() }, enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = colors.error),
                modifier = Modifier.testTag("$prefix-avatar-remove-confirm")) { Text("Удалить фото") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }, enabled = !busy,
                modifier = Modifier.testTag("$prefix-avatar-remove-cancel")) { Text("Отмена") } })
    }
}
