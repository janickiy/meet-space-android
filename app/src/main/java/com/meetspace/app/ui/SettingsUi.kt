package com.meetspace.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meetspace.core.designsystem.MeetSpaceTokens

enum class SpeakerPreference { DEFAULT, SPEAKER, HEADSET }

/** Local device preferences; hiding a video changes display, not server subscriptions. */
data class AccountDevicePreferences(
    val autoMute: Boolean = true,
    val cameraOff: Boolean = true,
    val showSelf: Boolean = true,
    val hideParticipantVideo: Boolean = false,
    val speaker: SpeakerPreference = SpeakerPreference.DEFAULT,
    val textScale: Float = 1f,
)

data class AppSettingsUiState(
    val versionName: String,
    val versionCode: Int,
    val notificationsAllowed: Boolean,
    val cameraAllowed: Boolean,
    val microphoneAllowed: Boolean,
    val pushConfigured: Boolean,
    val pushEnabled: Boolean,
    val devicePreferences: AccountDevicePreferences = AccountDevicePreferences(),
    val notificationSoundEnabled: Boolean = true,
)

data class AppSettingsActions(
    val openNotifications: () -> Unit = {},
    val openSystemSettings: () -> Unit = {},
    val updateDevicePreferences: (AccountDevicePreferences) -> Unit = {},
    val updateNotificationSound: (Boolean) -> Unit = {},
)

/** App settings use real account notification preferences and Android permission controls. */
@Composable
fun AppSettings(state: AppSettingsUiState, actions: AppSettingsActions) {
    var privacy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("app-settings"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsCard {
            Text("Уведомления", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(if (state.notificationsAllowed) "Системные уведомления разрешены" else "Системные уведомления выключены", modifier = Modifier.testTag("settings-notification-permission"))
            Text(when {
                !state.pushConfigured -> "Фоновые push пока не подключены. Сообщения и центр уведомлений доступны в приложении."
                state.pushEnabled -> "Фоновые push включены для текущего аккаунта."
                else -> "Фоновые push выключены. Их можно включить в настройках уведомлений."
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("settings-push-state"))
            OutlinedButton(actions.openNotifications, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings-notifications")) {
                Icon(Icons.Outlined.NotificationsNone, contentDescription = null); Spacer(Modifier.width(12.dp)); Text("Центр и настройки уведомлений")
            }
        }
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Разрешения устройства", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            Text("Камера: ${if (state.cameraAllowed) "доступ разрешён" else "доступ не разрешён"}")
            Text("Микрофон: ${if (state.microphoneAllowed) "доступ разрешён" else "доступ не разрешён"}")
            Text("Камера и микрофон запрашиваются перед звонком. Показ экрана каждый раз требует отдельного разрешения Android. Для скачивания вы сами выбираете место сохранения.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(actions.openSystemSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings-system-permissions")) { Text("Открыть настройки Android") }
        }
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("О MeetSpace", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            Text("Версия ${state.versionName} (${state.versionCode})", modifier = Modifier.testTag("settings-version"))
            Text("Пространство вашей команды", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton({ privacy = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings-privacy")) {
                Icon(Icons.Outlined.PrivacyTip, contentDescription = null); Spacer(Modifier.width(12.dp)); Text("Конфиденциальность и данные")
            }
        }
    }
    if (privacy) AlertDialog(
        onDismissRequest = { privacy = false },
        title = { Text("Ваши данные в MeetSpace") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()).testTag("settings-privacy-content"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PrivacyContents()
        } },
        confirmButton = { TextButton({ privacy = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("settings-privacy-close")) { Text("Понятно") } },
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = MeetSpaceTokens.CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun PrivacyContents() {
            Text("Профиль, встречи, сообщения и материалы находятся на выбранном сервере команды. Правила хранения и удаления определяет его администратор.")
            Text("Приложение получает записи по временным ссылкам хранилища. Такие ссылки не публикуются и не копируются в буфер обмена.")
            Text("Камера, микрофон и показ экрана передают данные во время звонка. Участники могут видеть и слышать вас в соответствии с выбранными настройками звонка.")
            Text("Сеанс входа защищён Android Keystore. Переписка и ссылки записей хранятся в памяти приложения; при выходе доступ к ним очищается. Скачанные вами файлы остаются в выбранном месте — удалить их можно через файловый менеджер.")
            Text("Текст сообщений не показывается в системных уведомлениях. Если включены фоновые push, токен устройства связывается с аккаунтом для доставки уведомлений.")
            Text("Для установки сервера SSH подключается к указанному вами адресу. Ключ сервера проверяется перед передачей учётных данных.")
}
