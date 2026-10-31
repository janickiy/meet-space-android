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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.app.AvatarImage
import com.meetspace.app.AvatarEditTarget
import com.meetspace.app.rememberAvatarPicker
import com.meetspace.core.model.NotificationPreferences
import com.meetspace.core.model.RepositoryState
import com.meetspace.core.model.ThemeMode
import java.net.URI

private enum class AccountSection(val title: String, val hint: String, val icon: ImageVector) {
    PROFILE("Профиль", "Имя и данные аккаунта", Icons.Outlined.PersonOutline),
    ACCOUNTS("Аккаунты", "Добавление и переключение", Icons.Outlined.People),
    ADD_ACCOUNT("Добавить аккаунт", "", Icons.Outlined.PersonAdd),
    AUDIO("Аудио", "Микрофон, динамик и звук", Icons.Outlined.MicNone),
    VIDEO("Видео", "Камера и участники", Icons.Outlined.Videocam),
    NOTIFICATIONS("Уведомления", "События и звук", Icons.Outlined.NotificationsNone),
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
    onSelectAccount: (String) -> Unit = {},
    onAddAccount: (String, String, String, String?, String) -> Unit = { _, _, _, _, _ -> },
    registeredAccountEmail: String? = null,
    onDeleteAccount: (String) -> Unit = {},
    onManagePush: (() -> Unit)? = null,
) {
    var section by rememberSaveable { mutableStateOf(AccountSection.entries.find { it.name.equals(initialSection?.replace('-', '_'), true) } ?: AccountSection.PROFILE) }
    var detailOpen by rememberSaveable { mutableStateOf(initialSection != null) }
    var logout by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    var deleteAccountDialog by remember { mutableStateOf(false) }
    var deleteAcknowledged by remember(deleteAccountDialog) { mutableStateOf(false) }
    var deletePassword by remember(repository.selectedProfileId) { mutableStateOf("") }
    val user = repository.session?.user
    val origin = repository.selectedProfile?.origin.orEmpty()
    val name = user?.displayLabel.orEmpty()
    var avatarError by remember(origin, user?.id) { mutableStateOf<String?>(null) }
    val avatarEnabled = user != null && !busy && !meetings.profileBusy && !meetings.actionBusy
    val pickAvatar = rememberAvatarPicker("$origin|${user?.id}", avatarEnabled,
        onPicked = { mime, bytes -> avatarError = null; meetingsActions.updateAvatar(mime, bytes) },
        onError = { avatarError = it },
        existingAvatar = user?.let { AvatarEditTarget(it.id, it.avatarVersion) },
        title = "Фото профиля", operationRevision = meetings.avatarRevision, operationError = meetings.error)
    val host = remember(origin) { runCatching { URI(origin).host }.getOrNull() ?: origin }
    val density = LocalDensity.current
    val device = settings.devicePreferences
    LaunchedEffect(initialSection) {
        AccountSection.entries.find { it.name.equals(initialSection?.replace('-', '_'), true) }?.let {
            section = it; detailOpen = true
        }
    }
    LaunchedEffect(section, detailOpen) { if (section == AccountSection.NOTIFICATIONS) messagingActions.loadNotifications() }
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * device.textScale.coerceIn(.75f, 2f)),
        LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp)) {
        BoxWithConstraints(Modifier.fillMaxSize().testTag("profile")) {
            val wide = maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f) >= 780.dp
            val panelPadding = (maxWidth * .04f).coerceIn(26.dp, 54.dp)
            val sidebarWidth = if (maxWidth >= 1100.dp) 270.dp else 240.dp
            val back = { if (section == AccountSection.ADD_ACCOUNT) section = AccountSection.ACCOUNTS else detailOpen = false }
            BackHandler(section == AccountSection.ADD_ACCOUNT || !wide && detailOpen) { if (!busy) back() }
            val choose: (AccountSection) -> Unit = { section = it; detailOpen = true }
            val shape = RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp)
            Surface(Modifier.fillMaxSize().padding(horizontal = if (wide) 24.dp else 0.dp).testTag(if (wide) "profile-wide" else "profile-compact"),
                shape = shape, color = if (section == AccountSection.NOTIFICATIONS) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface,
                border = if (wide) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null) {
                Row(Modifier.fillMaxSize()) {
                    if (wide || !detailOpen) {
                        AccountSidebar(name, user?.email.orEmpty(), host, section, wide, busy, repository.profiles.filter { it.accountId != null },
                            repository.selectedProfileId, onSelectAccount, choose, { logout = true }, { privacy = true },
                            settings.versionName, Modifier.then(if (wide) Modifier.width(sidebarWidth) else Modifier.fillMaxWidth()).fillMaxHeight(), user?.id, user?.avatarVersion)
                    }
                    if (wide || detailOpen) {
                        Column(Modifier.weight(1f).fillMaxHeight().wrapContentWidth(Alignment.Start).widthIn(max = 860.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(horizontal = if (wide) panelPadding else 20.dp, vertical = if (wide) 36.dp else 18.dp)
                            .testTag("account-panel-${section.name.lowercase()}"), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            if (!wide) {
                                TextButton({ back() }, enabled = !busy, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("account-back")) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(if (section == AccountSection.ADD_ACCOUNT) "Аккаунты" else "Все настройки", fontSize = 12.6.sp)
                                }
                                Spacer(Modifier.height(22.dp))
                            }
                            when (section) {
                                AccountSection.PROFILE -> {
                                    AccountHeading("Ваш профиль", "Как вас видят участники встречи.", wide)
                                    AvatarPhotoCard(name, user?.id, user?.avatarVersion, avatarEnabled, meetings.profileBusy,
                                        pickAvatar, { avatarError = null; meetingsActions.deleteAvatar() },
                                        Modifier.padding(bottom = 24.dp))
                                    avatarError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(bottom = 16.dp).testTag("account-avatar-error")) }
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
                                AccountSection.ACCOUNTS -> AccountListPanel(repository, wide, busy, onSelectAccount,
                                    { section = AccountSection.ADD_ACCOUNT }, { deleteAccountDialog = true })
                                AccountSection.ADD_ACCOUNT -> AccountAddPanel(origin, wide, busy, registeredAccountEmail, onAddAccount,
                                    { section = AccountSection.ACCOUNTS })
                                AccountSection.AUDIO -> AccountAudioPanel(settings, settingsActions)
                                AccountSection.VIDEO -> AccountVideoPanel(name, settings, settingsActions)
                                AccountSection.NOTIFICATIONS -> key(origin, user?.id) {
                                    AccountNotificationHeading(wide)
                                    AccountNotificationPanel(messaging, messagingActions, settings, settingsActions, wide)
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
    if (deleteAccountDialog) AlertDialog(onDismissRequest = { if (!busy) { deleteAccountDialog = false; deletePassword = "" } },
        title = { Text("Удалить аккаунт MeetSpace?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Удалить ${user?.email.orEmpty()} с сервера $host? Удаление нельзя отменить. Общие сообщения и записи других участников сохранятся. Введите пароль этого аккаунта.")
            OutlinedTextField(value = deletePassword, onValueChange = { deletePassword = it.take(1024) }, singleLine = true,
                label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("account-delete-password"))
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(deleteAcknowledged, { deleteAcknowledged = it }, modifier = Modifier.testTag("account-delete-acknowledge")); Text("Понимаю, что аккаунт будет удалён без возможности восстановления.", style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { TextButton(onClick = { val password = deletePassword; deletePassword = ""; deleteAccountDialog = false; onDeleteAccount(password) },
            enabled = !busy && deleteAcknowledged && deletePassword.isNotEmpty(), modifier = Modifier.testTag("account-delete-confirm")) { Text("Удалить") } },
        dismissButton = { TextButton({ deleteAccountDialog = false; deletePassword = "" }, enabled = !busy) { Text("Отмена") } })
    if (privacy) AlertDialog(onDismissRequest = { privacy = false }, title = { Text("MeetSpace ${settings.versionName}") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { PrivacyContents() } }, confirmButton = { TextButton({ privacy = false }) { Text("Готово") } })
}

@Composable private fun AccountListPanel(repository: RepositoryState, wide: Boolean, busy: Boolean,
    select: (String) -> Unit, add: () -> Unit, delete: () -> Unit) {
    val accounts = repository.profiles.filter { it.accountId != null }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            AccountHeading("Аккаунты", "Выберите аккаунт для встреч и общения.", wide)
        }
        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Text(accounts.size.toString(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        accounts.forEach { account ->
            val current = account.id == repository.selectedProfileId
            val name = (if (current) repository.session?.user?.displayLabel else null)
                ?: account.accountName ?: account.accountEmail.orEmpty()
            val host = runCatching { URI(account.origin).authority }.getOrNull() ?: account.origin
            Surface(onClick = { if (!current) select(account.id) }, enabled = !busy,
                shape = RoundedCornerShape(if (wide) 18.dp else 15.dp),
                color = if (current) lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primaryContainer, .45f) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().testTag("account-card-${account.id}")) {
                Row(Modifier.padding(horizontal = if (wide) 18.dp else 12.dp, vertical = if (wide) 18.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (wide) 14.dp else 10.dp)) {
                    AccountAvatar(name, if (wide) 54 else 42, if (current) repository.session?.user?.id else null, if (current) repository.session?.user?.avatarVersion else null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(account.accountEmail.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Dns, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (current) "Текущий аккаунт" else "Нажмите, чтобы переключиться", style = MaterialTheme.typography.bodySmall,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (current) Icons.Outlined.CheckCircleOutline else Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(24.dp),
                        tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Button(add, enabled = !busy, shape = RoundedCornerShape(13.dp),
        modifier = Modifier.padding(top = 18.dp).then(if (wide) Modifier else Modifier.fillMaxWidth()).heightIn(min = 50.dp).testTag("account-add")) {
        Icon(Icons.Outlined.Add, null, Modifier.size(19.dp)); Spacer(Modifier.width(9.dp)); Text("Добавить аккаунт", fontSize = 13.2.sp)
    }
    AccountNote("Можно добавить аккаунты с одного или разных серверов. История общения и сессии входа хранятся отдельно.")
    HorizontalDivider(Modifier.padding(top = 30.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(35.dp).background(MaterialTheme.colorScheme.error.copy(alpha = .09f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        }
        Column(Modifier.weight(1f)) {
            Text("Удаление аккаунта", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            val host = runCatching { URI(repository.selectedProfile?.origin.orEmpty()).authority }.getOrNull().orEmpty()
            Text("Удалить ${repository.session?.user?.email.orEmpty()} с сервера $host.", fontSize = 11.6.sp, lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
            TextButton(delete, enabled = !busy && repository.selectedProfile?.accountId != null, contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("account-delete")) {
                Text("Удалить аккаунт с сервера", fontSize = 12.sp); Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.NorthEast, null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable private fun AccountEyebrow(label: String) {
    Text(label, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable private fun AccountAddPanel(origin: String, wide: Boolean, busy: Boolean, registeredEmail: String?,
    submit: (String, String, String, String?, String) -> Unit, cancel: () -> Unit) {
    var register by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf(origin) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords deliberately never enter saved-instance state or preferences.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    LaunchedEffect(registeredEmail) {
        if (registeredEmail != null) { email = registeredEmail; register = false; password = ""; confirmation = "" }
    }
    AccountEyebrow("ЕЩЁ ОДНО ПРОСТРАНСТВО")
    AccountHeading("Добавить аккаунт", if (register) "Создайте аккаунт на выбранном сервере." else "Войдите в существующий аккаунт.", wide)
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(13.dp)).padding(4.dp).selectableGroup()) {
        listOf(false to "Войти", true to "Создать аккаунт").forEach { (mode, label) ->
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (mode == register) MaterialTheme.colorScheme.surface else Color.Transparent)
                .selectable(mode == register, enabled = !busy, role = Role.Tab, onClick = { register = mode; password = ""; confirmation = "" })
                .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp).testTag(if (mode) "account-add-register-tab" else "account-add-login-tab"), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 12.6.sp, fontWeight = FontWeight.SemiBold, color = if (mode == register) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    val fields = buildList<@Composable () -> Unit> {
        if (register) add { AccountField("Имя для встреч", name, { name = it.take(200) }, "account-add-name", !busy) }
        add { AccountField("Электронная почта", email, { email = it.take(254) }, "account-add-email", !busy, keyboardType = KeyboardType.Email) }
        add { AccountField("Пароль", password, { password = it.take(1024) }, "account-add-password", !busy,
            hint = if (register) "От 8 до 128 символов." else null, keyboardType = KeyboardType.Password, password = true) }
        if (register) add { AccountField("Повторите пароль", confirmation, { confirmation = it.take(1024) }, "account-add-confirm", !busy, keyboardType = KeyboardType.Password, password = true) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val twoColumns = maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f) >= 560.dp
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            AccountField("Адрес сервера", server, { server = it.take(2048) }, "account-add-server", !busy, keyboardType = KeyboardType.Uri)
            fields.chunked(if (twoColumns) 2 else 1).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { field -> Box(Modifier.weight(1f)) { field() } }
                    if (twoColumns && row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Button({ submit(server, email, password, name.takeIf { register }, confirmation) },
                enabled = !busy && server.isNotBlank() && email.isNotBlank() && password.isNotEmpty() && (!register || name.isNotBlank() && confirmation.isNotEmpty()),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("account-add-submit")) {
                Text(if (busy) "Подключаемся…" else if (register) "Создать и добавить" else "Войти и добавить", fontSize = 13.2.sp)
                Spacer(Modifier.width(9.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
            }
            OutlinedButton(cancel, enabled = !busy, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("account-add-cancel")) { Text("Отмена") }
        }
    }
}

@Composable private fun AccountSidebar(name: String, email: String, host: String, section: AccountSection, wide: Boolean, busy: Boolean,
    profiles: List<com.meetspace.core.model.ServerProfile>, selectedProfileId: String?, onSelectAccount: (String) -> Unit,
    choose: (AccountSection) -> Unit, logout: () -> Unit, about: () -> Unit, version: String, modifier: Modifier, avatarId: String?, avatarVersion: String?) {
    var accountMenu by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.background(if (wide) lerp(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface, .35f) else MaterialTheme.colorScheme.surface)) {
        val availableHeight = maxHeight
        val accent = MaterialTheme.colorScheme.primary
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = availableHeight).padding(horizontal = if (wide) if (maxWidth >= 270.dp) 18.dp else 14.dp else 20.dp, vertical = if (wide) 26.dp else 24.dp)) {
            Row(Modifier.fillMaxWidth().clickable { choose(AccountSection.PROFILE) }.testTag("profile-edit").padding(horizontal = if (wide) 7.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (wide) 10.dp else 14.dp)) {
                AccountAvatar(name, if (wide) 42 else 54, avatarId, avatarVersion)
                Column(Modifier.weight(1f)) { Text(name, fontSize = if (wide) 12.6.sp else 16.sp, lineHeight = if (wide) 18.sp else 23.sp, fontWeight = FontWeight.SemiBold); Text(email, fontSize = if (wide) 10.8.sp else 12.6.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp)) }
            }
            if (wide && profiles.size > 1) Box(Modifier.padding(start = 48.dp, top = 4.dp)) {
                TextButton(onClick = { accountMenu = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp), modifier = Modifier.testTag("account-switch-menu")) {
                    Text("Сменить аккаунт", fontSize = 10.8.sp); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                    profiles.forEach { profile ->
                        DropdownMenuItem(text = { Text(profile.accountName ?: profile.accountEmail ?: "${profile.origin}", maxLines = 1) },
                            onClick = { accountMenu = false; if (profile.id != selectedProfileId) onSelectAccount(profile.id) },
                            leadingIcon = { if (profile.id == selectedProfileId) Icon(Icons.Outlined.Check, null) else Icon(Icons.Outlined.PersonOutline, null) })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Управление аккаунтами") }, onClick = { accountMenu = false; choose(AccountSection.ACCOUNTS) },
                        leadingIcon = { Icon(Icons.Outlined.Settings, null) })
                }
            }
            Spacer(Modifier.height(if (wide) 30.dp else 26.dp))
            Text("НАСТРОЙКИ АККАУНТА", fontSize = if (wide) 8.5.sp else 9.5.sp, lineHeight = if (wide) 13.sp else 14.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = if (wide) 12.dp else 0.dp, bottom = 10.dp).heightIn(min = (if (wide) 13.dp else 14.dp) * LocalDensity.current.fontScale))
            AccountSection.entries.filter { it != AccountSection.CONNECTION && it != AccountSection.ADD_ACCOUNT }.forEach { item ->
                val active = wide && (section == item || section == AccountSection.ADD_ACCOUNT && item == AccountSection.ACCOUNTS)
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

@Composable private fun AccountAvatar(name: String, size: Int, id: String? = null, version: String? = null) {
    AvatarImage(name, id, version, size = size.dp, modifier = Modifier.testTag("account-avatar"),
        shape = RoundedCornerShape(if (size <= 42) 14.dp else if (size >= 64) 20.dp else 18.dp))
}
@Composable private fun AccountHeading(title: String, subtitle: String, wide: Boolean, avatar: String? = null, avatarId: String? = null, avatarVersion: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = if (wide) 32.dp else 30.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = if (wide) 23.sp else 21.sp, lineHeight = 29.sp, letterSpacing = (-.5).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }); Text(subtitle, fontSize = 13.2.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
        avatar?.let { AccountAvatar(it, if (wide) 70 else 64, avatarId, avatarVersion) }
    }
}
@Composable private fun AccountField(label: String, value: String, change: (String) -> Unit, tag: String, enabled: Boolean = true, readOnly: Boolean = false, hint: String? = null, error: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text, password: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.heightIn(min = 21.dp * LocalDensity.current.fontScale))
        BasicTextField(value, change, singleLine = true, enabled = enabled, readOnly = readOnly, keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            textStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp, color = if (readOnly) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
                .background(if (readOnly) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface)
                .border(if (focused) 2.dp else 1.dp, if (error) MaterialTheme.colorScheme.error else if (focused) MaterialTheme.colorScheme.primary else lerp(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.onSurfaceVariant, .25f), RoundedCornerShape(12.dp))
                .onFocusChanged { focused = it.isFocused }
                .testTag(tag).semantics { contentDescription = label },
            decorationBox = { field -> Row(Modifier.padding(start = 14.dp, end = if (password) 2.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) { field() }
                if (password) IconButton({ reveal = !reveal }, enabled = enabled) {
                    Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        if (reveal) "Скрыть $label" else "Показать $label", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } })
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable private fun AccountSave(label: String, tag: String, enabled: Boolean, save: () -> Unit) {
    Button(save, enabled = enabled, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) { Text(label, style = MaterialTheme.typography.labelLarge) }
}
@Composable private fun AccountSectionLabel(label: String) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 16.dp)) }
@Composable private fun AccountDivider() { HorizontalDivider(Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.outlineVariant) }
@Composable private fun AccountNote(text: String) {
    Surface(Modifier.padding(top = 20.dp).fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable internal fun AccountToggle(label: String, value: Boolean, change: (Boolean) -> Unit, hint: String? = null, enabled: Boolean = true, tag: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = change).testTag(tag)
        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.bodyMedium); hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) } }
        val track = (if (value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant).copy(alpha = if (enabled) 1f else .45f)
        Box(Modifier.size(48.dp)
            .drawBehind {
                val top = (size.height - 26.dp.toPx()) / 2f
                drawRoundRect(track, Offset(2.dp.toPx(), top), Size(44.dp.toPx(), 26.dp.toPx()), CornerRadius(13.dp.toPx()))
                drawCircle(Color.White.copy(alpha = if (enabled) 1f else .6f), 10.dp.toPx(), Offset((if (value) 33 else 15).dp.toPx(), size.height / 2))
            })
    }
}
@Composable private fun AccountChoice(label: String, value: String, options: List<String>, change: (String) -> Unit, tag: String) {
    var open by remember { mutableStateOf(false) }
    Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
    Box { OutlinedButton({ open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, lerp(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.onSurfaceVariant, .25f)), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
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
