package com.meetspace.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.core.model.NotificationPreferences
import com.meetspace.core.model.RepositoryState
import com.meetspace.core.model.ThemeMode
import java.net.URI

private enum class AccountSection(val title: String, val hint: String, val icon: ImageVector) {
    PROFILE("Профиль", "Имя и данные аккаунта", Icons.Outlined.PersonOutline),
    AUDIO("Аудио", "Микрофон, динамик и звук", Icons.Outlined.MicNone),
    VIDEO("Видео", "Камера и участники", Icons.Outlined.Videocam),
    NOTIFICATIONS("Уведомления", "События и каналы доставки", Icons.Outlined.NotificationsNone),
    APPEARANCE("Оформление", "Тема и размер текста", Icons.Outlined.Palette),
    CONNECTION("Подключение", "Адрес сервера", Icons.Outlined.Dns),
}

/** A bounded sidebar/detail layout; account and notification changes still use their repositories. */
@Composable
fun ProfileWorkspace(
    repository: RepositoryState,
    meetings: MeetingsUiState,
    meetingsActions: MeetingsActions,
    settings: AppSettingsUiState,
    settingsActions: AppSettingsActions,
    busy: Boolean = false,
    onTheme: (ThemeMode) -> Unit,
    onLogout: () -> Unit,
    onServers: () -> Unit,
    onFolders: () -> Unit,
    messaging: MessagingUiState = MessagingUiState(),
    messagingActions: MessagingActions = MessagingActions(),
    onServerAddress: ((String) -> Unit)? = null,
    initialSection: String? = null,
) {
    var section by rememberSaveable { mutableStateOf(AccountSection.entries.find { it.name.equals(initialSection, true) } ?: AccountSection.PROFILE) }
    var detailOpen by rememberSaveable { mutableStateOf(initialSection != null) }
    var logout by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    val user = repository.session?.user
    val origin = repository.selectedProfile?.origin.orEmpty()
    val name = user?.displayLabel.orEmpty()
    val host = remember(origin) { runCatching { URI(origin).host }.getOrNull() ?: origin }
    val density = LocalDensity.current
    val device = settings.devicePreferences
    LaunchedEffect(section, detailOpen) { if (section == AccountSection.NOTIFICATIONS) messagingActions.loadNotifications() }
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * device.textScale.coerceIn(.75f, 2f)),
        LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp)) {
        BoxWithConstraints(Modifier.fillMaxSize().testTag("profile")) {
            val wide = maxWidth >= 780.dp
            val panelPadding = (maxWidth * .04f).coerceIn(26.dp, 54.dp)
            val sidebarWidth = if (maxWidth >= 1100.dp) 270.dp else 240.dp
            BackHandler(!wide && detailOpen) { detailOpen = false }
            val choose: (AccountSection) -> Unit = { section = it; detailOpen = true }
            val shape = RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp)
            Surface(Modifier.fillMaxSize().padding(horizontal = if (wide) 24.dp else 0.dp).testTag(if (wide) "profile-wide" else "profile-compact"),
                shape = shape, color = MaterialTheme.colorScheme.surface,
                border = if (wide) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null) {
                Row(Modifier.fillMaxSize()) {
                    if (wide || !detailOpen) {
                        AccountSidebar(name, user?.email.orEmpty(), host, section, wide, busy, choose, { logout = true }, { privacy = true },
                            settings.versionName, Modifier.then(if (wide) Modifier.width(sidebarWidth) else Modifier.fillMaxWidth()).fillMaxHeight())
                    }
                    if (wide || detailOpen) {
                        Column(Modifier.weight(1f).fillMaxHeight().wrapContentWidth(Alignment.Start).widthIn(max = 860.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(horizontal = if (wide) panelPadding else 20.dp, vertical = if (wide) 36.dp else 18.dp)
                            .testTag("account-panel-${section.name.lowercase()}"), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            if (!wide) {
                                TextButton({ detailOpen = false }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 44.dp).testTag("account-back")) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Все настройки", fontSize = 12.6.sp)
                                }
                                Spacer(Modifier.height(22.dp))
                            }
                            when (section) {
                                AccountSection.PROFILE -> {
                                    AccountHeading("Ваш профиль", "Как вас видят участники встречи.", wide, name)
                                    var draft by remember(user?.id, user?.displayName) { mutableStateOf(user?.displayName.orEmpty()) }
                                    val clean = draft.trim()
                                    val valid = clean.codePointCount(0, clean.length) in 1..100 && clean.none { Character.isISOControl(it) }
                                    AccountField("Имя для встреч", draft, { draft = it.take(200) }, "account-name", !meetings.profileBusy,
                                        hint = if (valid || draft.isEmpty()) "От 1 до 100 символов." else "Введите имя от 1 до 100 символов.", error = !valid && draft.isNotEmpty())
                                    Spacer(Modifier.height(26.dp))
                                    AccountField("Email", user?.email.orEmpty(), {}, "account-email", readOnly = true,
                                        hint = "Адрес используется для входа. Изменение email пока недоступно.")
                                    Spacer(Modifier.height(26.dp))
                                    AccountSave("Сохранить имя", "account-save", valid && clean != user?.displayName && !busy && !meetings.profileBusy && !meetings.actionBusy) { meetingsActions.updateProfile(clean) }
                                    meetings.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
                                    meetings.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
                                    Spacer(Modifier.height(if (wide) 36.dp else 30.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        val registration = user?.createdAt?.let { runCatching { java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy 'г.,' HH:mm", java.util.Locale.forLanguageTag("ru")).withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(it)) }.getOrNull() }
                                        Text(registration?.let { "Регистрация: $it" } ?: "Аккаунт в пространстве команды", fontSize = 11.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                AccountSection.AUDIO -> AccountAudioPanel(settings, settingsActions)
                                AccountSection.VIDEO -> AccountVideoPanel(name, settings, settingsActions)
                                AccountSection.NOTIFICATIONS -> {
                                    AccountHeading("Уведомления", "Выберите события и каналы доставки.", wide)
                                    val current = messaging.notificationPreferences.data
                                    var draft by remember(current) { mutableStateOf(current) }
                                    if (current == null) {
                                        Text(messaging.notificationPreferences.error ?: "Загружаем настройки уведомлений…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        TextButton(messagingActions.loadNotifications) { Text("Обновить") }
                                    } else draft?.let { prefs ->
                                        AccountSectionLabel("События")
                                        AccountCheck("Приглашения и изменения встреч", prefs.invitation, { draft = prefs.copy(invitation = it) }, "notification-pref-invitation")
                                        AccountCheck("Напоминания о встречах", prefs.reminder, { draft = prefs.copy(reminder = it) }, "notification-pref-reminder")
                                        AccountCheck("Готовность записи", prefs.recording, { draft = prefs.copy(recording = it) }, "notification-pref-recording")
                                        AccountCheck("Расшифровка и итоги встречи", prefs.summary, { draft = prefs.copy(summary = it) }, "notification-pref-summary")
                                        AccountDivider(); AccountSectionLabel("Звук уведомлений")
                                        AccountNotificationSound(settingsActions)
                                        AccountDivider(); AccountSectionLabel("Каналы доставки")
                                        AccountToggle("Email", prefs.email, { draft = prefs.copy(email = it) }, "Доставка зависит от настроек почты на сервере", tag = "account-notification-email")
                                        AccountToggle("Push-уведомления", settings.pushEnabled, { settingsActions.openNotifications() }, if (settings.pushConfigured) "Доставка на это устройство" else "Не настроены на этом сервере", settings.pushConfigured, "account-notification-push")
                                        Spacer(Modifier.height(18.dp)); AccountSave("Сохранить настройки", "account-notification-save", !messaging.busy && prefs != current) { messagingActions.saveNotificationPreferences(prefs) }
                                    }
                                    messaging.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
                                    messaging.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
                                    TextButton(settingsActions.openNotifications, modifier = Modifier.padding(top = 12.dp).testTag("settings-notifications")) { Text("Открыть центр уведомлений") }
                                }
                                AccountSection.APPEARANCE -> {
                                    AccountHeading("Оформление", "Настройте MeetSpace под себя.", wide)
                                    AccountSectionLabel("Тема")
                                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        ThemeCard(ThemeMode.LIGHT, repository.theme, onTheme, wide, Modifier.weight(1f))
                                        ThemeCard(ThemeMode.DARK, repository.theme, onTheme, wide, Modifier.weight(1f))
                                    }
                                    TextButton({ onTheme(ThemeMode.SYSTEM) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("theme-system")) { Text("Как на устройстве", fontSize = 12.sp) }
                                    AccountDivider(); AccountSectionLabel("Внешний вид")
                                    val scales = listOf(.75f, .9f, 1f, 1.1f, 1.25f, 1.5f, 2f)
                                    AccountChoice("Размер текста", "${(device.textScale * 100).toInt()}%", scales.map { "${(it * 100).toInt()}%" }, { value -> settingsActions.updateDevicePreferences(device.copy(textScale = value.removeSuffix("%").toFloat() / 100f)) }, "account-text-scale")
                                    Column(Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("ПРЕДПРОСМОТР", fontSize = 10.sp, letterSpacing = 1.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Удобный размер для важных разговоров", fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Все ваши встречи и сообщения — в одном месте.", fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                AccountSection.CONNECTION -> {
                                    AccountHeading("Подключение", "Адрес сервера вашего MeetSpace.", wide)
                                    var draft by remember(origin) { mutableStateOf(origin) }
                                    val clean = draft.trim().trimEnd('/')
                                    val valid = runCatching { URI(clean).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null && (it.path.isNullOrEmpty() || it.path == "/") } }.getOrDefault(false)
                                    AccountField("Адрес сервера", draft, { draft = it.take(2048) }, "account-server-address", !busy,
                                        hint = if (!valid) "Введите один адрес HTTPS без логина, пароля и параметров." else null, error = !valid, keyboardType = KeyboardType.Uri)
                                    Spacer(Modifier.height(26.dp)); AccountSave("Сохранить адрес", "account-save-connection", valid && clean != origin.trimEnd('/') && !busy && onServerAddress != null) { onServerAddress?.invoke(clean) }
                                    AccountNote("При изменении адреса проверим сервер. Для другого пространства потребуется войти снова.")
                                    TextButton(onServers, enabled = !busy, modifier = Modifier.testTag("manage-servers")) { Text("Сохранённые серверы") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text("Выйти из аккаунта?") }, text = { Text("Чтобы вернуться в пространство, потребуется войти снова.") },
        confirmButton = { TextButton({ logout = false; onLogout() }, enabled = !busy, modifier = Modifier.testTag("logout-confirm")) { Text("Выйти") } }, dismissButton = { TextButton({ logout = false }) { Text("Отмена") } })
    if (privacy) AlertDialog(onDismissRequest = { privacy = false }, title = { Text("MeetSpace ${settings.versionName}") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { PrivacyContents() } }, confirmButton = { TextButton({ privacy = false }) { Text("Готово") } })
}

@Composable private fun AccountSidebar(name: String, email: String, host: String, section: AccountSection, wide: Boolean, busy: Boolean,
    choose: (AccountSection) -> Unit, logout: () -> Unit, about: () -> Unit, version: String, modifier: Modifier) {
    BoxWithConstraints(modifier.background(if (wide) lerp(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface, .35f) else MaterialTheme.colorScheme.surface)) {
        val availableHeight = maxHeight
        val accent = MaterialTheme.colorScheme.primary
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = availableHeight).padding(horizontal = if (wide) if (maxWidth >= 270.dp) 18.dp else 14.dp else 20.dp, vertical = if (wide) 26.dp else 24.dp)) {
            Row(Modifier.fillMaxWidth().clickable { choose(AccountSection.PROFILE) }.testTag("profile-edit").padding(horizontal = if (wide) 7.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (wide) 10.dp else 14.dp)) {
                AccountAvatar(name, if (wide) 42 else 54)
                Column(Modifier.weight(1f)) { Text(name, fontSize = if (wide) 12.6.sp else 16.sp, lineHeight = if (wide) 18.sp else 23.sp, fontWeight = FontWeight.SemiBold); Text(email, fontSize = if (wide) 10.8.sp else 12.6.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp)) }
            }
            Spacer(Modifier.height(if (wide) 30.dp else 26.dp))
            Text("НАСТРОЙКИ АККАУНТА", fontSize = if (wide) 8.5.sp else 9.5.sp, lineHeight = if (wide) 13.sp else 14.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = if (wide) 12.dp else 0.dp, bottom = 10.dp).heightIn(min = (if (wide) 13.dp else 14.dp) * LocalDensity.current.fontScale))
            AccountSection.entries.filter { it != AccountSection.CONNECTION }.forEach { item ->
                val active = wide && section == item
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(if (wide) 10.dp else 0.dp)).background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .drawBehind { if (active) drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
                    .clickable { choose(item) }.heightIn(min = if (wide) 52.dp else 74.dp).testTag("profile-${item.name.lowercase()}")
                    .padding(horizontal = if (wide) 12.dp else 0.dp, vertical = if (wide) 13.dp else 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    Box(Modifier.size(if (wide) 22.dp else 40.dp).clip(RoundedCornerShape(13.dp)).background(if (wide) Color.Transparent else MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(item.icon, null, Modifier.size(21.dp), tint = if (wide && !active) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f)) { Text(item.title, fontSize = if (wide) 13.3.sp else 14.8.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        if (!wide) Text(item.hint, fontSize = 11.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!wide) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!wide) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(5.dp))
            }
            if (wide) Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(if (wide) 40.dp else 26.dp))
            Surface(onClick = { choose(AccountSection.CONNECTION) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("profile-server"), shape = RoundedCornerShape(15.dp),
                color = if (wide) if (section == AccountSection.CONNECTION) MaterialTheme.colorScheme.primaryContainer else Color.Transparent else MaterialTheme.colorScheme.background,
                border = if (wide) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(horizontal = if (wide) 6.dp else 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    Icon(Icons.Outlined.Dns, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) { Text("Подключение", fontSize = if (wide) 11.sp else 12.sp, fontWeight = FontWeight.SemiBold); Text(host, fontSize = if (wide) 10.sp else 10.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(logout, enabled = !busy, modifier = Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(min = 48.dp).testTag("logout")) {
                Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(10.dp)); Text("Выйти из аккаунта", fontSize = 12.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 16.dp).clickable(onClick = about).testTag("profile-about"), contentAlignment = Alignment.Center) {
                Text("MeetSpace · $version", fontSize = 10.5.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun AccountAvatar(name: String, size: Int) {
    val initials = name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(if (size <= 42) 14.dp else if (size >= 64) 20.dp else 18.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Text(initials, fontSize = if (size <= 42) 15.sp else if (size >= 64) 23.sp else 20.sp, lineHeight = if (size <= 42) 20.sp else 28.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}
@Composable private fun AccountHeading(title: String, subtitle: String, wide: Boolean, avatar: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = if (wide) 32.dp else 30.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = if (wide) 23.sp else 21.sp, lineHeight = 29.sp, letterSpacing = (-.5).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }); Text(subtitle, fontSize = 13.2.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
        avatar?.let { AccountAvatar(it, if (wide) 70 else 64) }
    }
}
@Composable private fun AccountField(label: String, value: String, change: (String) -> Unit, tag: String, enabled: Boolean = true, readOnly: Boolean = false, hint: String? = null, error: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.heightIn(min = 21.dp * LocalDensity.current.fontScale))
        BasicTextField(value, change, singleLine = true, enabled = enabled, readOnly = readOnly, keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            textStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp, color = if (readOnly) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(12.dp))
                .background(if (readOnly) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface)
                .border(if (focused) 2.dp else 1.dp, if (error) MaterialTheme.colorScheme.error else if (focused) MaterialTheme.colorScheme.primary else lerp(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.onSurfaceVariant, .65f), RoundedCornerShape(12.dp))
                .onFocusChanged { focused = it.isFocused }
                .testTag(tag).semantics { contentDescription = label },
            decorationBox = { field -> Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) { field() } })
        hint?.let { Text(it, fontSize = 11.6.sp, lineHeight = 19.sp, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.heightIn(min = 19.dp * LocalDensity.current.fontScale)) }
    }
}
@Composable private fun AccountSave(label: String, tag: String, enabled: Boolean, save: () -> Unit) {
    Button(save, enabled = enabled, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = .45f), disabledContentColor = MaterialTheme.colorScheme.onPrimary), modifier = Modifier.heightIn(min = 46.dp).testTag(tag)) { Text(label, fontSize = 13.2.sp) }
}
@Composable private fun AccountSectionLabel(label: String) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 16.dp)) }
@Composable private fun AccountDivider() { HorizontalDivider(Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.outlineVariant) }
@Composable private fun AccountNote(text: String) { Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) { Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Text(text, fontSize = 11.2.sp, lineHeight = 18.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun AccountToggle(label: String, value: Boolean, change: (Boolean) -> Unit, hint: String? = null, enabled: Boolean = true, tag: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f)) { Text(label, fontSize = 13.4.sp, lineHeight = 21.sp); hint?.let { Text(it, fontSize = 11.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) } }
        val track = (if (value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant).copy(alpha = if (enabled) 1f else .45f)
        Box(Modifier.size(48.dp).toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = change).testTag(tag).semantics { contentDescription = label }
            .drawBehind {
                val top = (size.height - 26.dp.toPx()) / 2f
                drawRoundRect(track, Offset(2.dp.toPx(), top), Size(44.dp.toPx(), 26.dp.toPx()), CornerRadius(13.dp.toPx()))
                drawCircle(Color.White.copy(alpha = if (enabled) 1f else .6f), 10.dp.toPx(), Offset((if (value) 33 else 15).dp.toPx(), size.height / 2))
            })
    }
}
@Composable private fun AccountCheck(label: String, value: Boolean, change: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().clickable { change(!value) }.padding(vertical = 4.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) { Checkbox(value, null); Spacer(Modifier.width(8.dp)); Text(label, fontSize = 13.4.sp, lineHeight = 21.sp) }
}
@Composable private fun AccountChoice(label: String, value: String, options: List<String>, change: (String) -> Unit, tag: String) {
    var open by remember { mutableStateOf(false) }
    Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
    Box { OutlinedButton({ open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag(tag), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, lerp(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.onSurfaceVariant, .65f)), contentPadding = PaddingValues(horizontal = 14.dp)) {
        Text(value, Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface); Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(open, { open = false }) { options.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { open = false; change(item) }) } }
    }
}
@Composable private fun ThemeCard(mode: ThemeMode, selected: ThemeMode, change: (ThemeMode) -> Unit, wide: Boolean, modifier: Modifier) {
    val active = mode == selected; val dark = mode == ThemeMode.DARK
    Column(modifier.border(if (active) 2.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(15.dp)).clip(RoundedCornerShape(15.dp)).background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
        .selectable(active, onClick = { change(mode) }, role = Role.RadioButton).testTag("theme-${mode.name.lowercase()}").padding(8.dp)) {
        Row(Modifier.fillMaxWidth().height(if (wide) 132.dp else 94.dp).clip(RoundedCornerShape(9.dp)).background(if (dark) Color(0xFF19171F) else Color(0xFFF7F6FA)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxHeight().weight(.24f).clip(RoundedCornerShape(5.dp)).background(if (dark) Color(0xFF40354E) else Color(0xFFE5DCED)))
            Column(Modifier.fillMaxHeight().weight(.76f).clip(RoundedCornerShape(5.dp)).background(if (dark) Color(0xFF302B39) else Color.White).padding(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.fillMaxWidth(.6f).height(5.dp).background(if (dark) Color(0xFFB4A7C6) else Color(0xFF7B6B8E), RoundedCornerShape(2.dp)))
                repeat(2) { Surface(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(4.dp), color = Color.Transparent, border = BorderStroke(1.dp, if (dark) Color(0xFF494151) else Color(0xFFE8E5EE))) {} }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 11.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (dark) Icons.Outlined.DarkMode else Icons.Outlined.LightMode, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(if (dark) "Тёмная" else "Светлая", fontSize = 12.sp, modifier = Modifier.weight(1f)); RadioButton(active, null, modifier = Modifier.size(20.dp))
        }
    }
}
