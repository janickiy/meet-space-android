package com.meetspace.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.app.SshWizardStep
import com.meetspace.app.SshWizardUiState
import com.meetspace.core.model.*
import com.meetspace.core.ssh.SshInputValidator

@Composable
fun SshWizardUi(
    state: SshWizardUiState,
    onProbe: (String, String, String, String) -> Unit,
    onConfirmTrust: () -> Unit,
    onForgetTrust: () -> Unit,
    onImportKey: (Uri) -> Unit,
    onClearKey: () -> Unit,
    onPasswordPreflight: (String, String) -> Unit,
    onKeyPreflight: (String, String) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    onExit: () -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier,
    onInstall: (() -> Unit)? = null,
    onReconnectInstall: (() -> Unit)? = null,
    onReuseAccess: () -> Unit = {},
) {
    val back = { if (state.busy) onCancel() else if (state.step == SshWizardStep.DETAILS) onExit() else onBack() }
    BackHandler(onBack = back)
    Scaffold(modifier.testTag("ssh-wizard"), contentWindowInsets = WindowInsets.safeDrawing, containerColor = MaterialTheme.colorScheme.background) { insets ->
        IntroFrame(message = listOf(state.step.name, state.error, state.notice).joinToString(), modifier = Modifier.padding(insets).imePadding(), install = true,
            topBar = {
                TextButton(onClick = back, contentPadding = PaddingValues(vertical = 10.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("ssh-back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.busy) "Отменить" else "Назад", fontSize = if (LocalOnboardingWide.current) 15.sp else 16.sp,
                        lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
                }
            },
            footer = "Пароль и приватный ключ хранятся только в памяти мастера проверки и установки. Они не записываются на устройство.",
        ) {
            CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(letterSpacing = 0.sp,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))) {
                Column(Modifier.fillMaxWidth()) {
                    OnboardingProgress(state.step.ordinal + 1)
                    Spacer(Modifier.height(32.dp))
                    SshHeading(state)
                    state.error?.let { SshMessage(it, true, onClearMessage); Spacer(Modifier.height(20.dp)) }
                    state.notice?.let { SshMessage(it, false, onClearMessage); Spacer(Modifier.height(20.dp)) }
                    onReconnectInstall?.let { reconnect ->
                        SshButton("Восстановить наблюдение за установкой", reconnect, !state.busy, primary = false)
                        Spacer(Modifier.height(20.dp))
                    }
                    if (state.busy) {
                        SshCard(Modifier.testTag("ssh-progress")) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(16.dp))
                            SshNote(state.operation ?: "Проверка…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            Spacer(Modifier.height(16.dp))
                            SshButton("Отменить проверку", onCancel, tag = "ssh-cancel", primary = false)
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                    when (state.step) {
                        SshWizardStep.DETAILS -> SshDetails(state, onProbe)
                        SshWizardStep.VERIFY_HOST -> SshHostVerification(state, onConfirmTrust, onForgetTrust)
                        SshWizardStep.AUTH -> SshAuth(state, onImportKey, onClearKey, onPasswordPreflight, onKeyPreflight, onReuseAccess)
                        SshWizardStep.RESULTS -> SshResults(state, onInstall, onBack)
                    }
                }
            }
        }
    }
}

@Composable
private fun SshHeading(state: SshWizardUiState) {
    val wide = LocalOnboardingWide.current
    val titleSize = if (!wide) 28.sp else if (LocalConfiguration.current.screenWidthDp <= 1000) 30.sp else 32.sp
    Text(when (state.step) {
        SshWizardStep.DETAILS -> "СОБСТВЕННАЯ ИНФРАСТРУКТУРА"
        SshWizardStep.VERIFY_HOST -> "ИДЕНТИЧНОСТЬ СЕРВЕРА"
        SshWizardStep.AUTH -> "ДОСТУП ПО SSH"
        SshWizardStep.RESULTS -> "ПЕРЕД УСТАНОВКОЙ"
    }, fontSize = if (wide) 11.sp else 12.sp, lineHeight = if (wide) 16.5.sp else 18.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
    Text(when (state.step) {
        SshWizardStep.DETAILS -> "Начнём с сервера."
        SshWizardStep.VERIFY_HOST -> "Знакомимся безопасно."
        SshWizardStep.AUTH -> "Доступ к серверу."
        SshWizardStep.RESULTS -> if (state.report?.hasBlockers == true) "Проверим ограничения." else "Всё по плану."
    }, fontSize = titleSize, lineHeight = titleSize * if (wide) 1.2f else 1.22f,
        letterSpacing = if (wide) (-1).sp else (-.8).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(12.dp))
    Text(when (state.step) {
        SshWizardStep.DETAILS -> "Нужен отдельный удалённый Linux VPS с публичным IP-адресом."
        SshWizardStep.VERIFY_HOST -> "При первом подключении проверьте отпечаток SSH-ключа сервера."
        SshWizardStep.AUTH -> "Подтвердите доступ для проверки сервера перед установкой."
        SshWizardStep.RESULTS -> "Ознакомьтесь с результатами диагностики сервера."
    }, fontSize = if (wide) 15.sp else 16.sp, lineHeight = if (wide) 24.75.sp else 26.4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(if (wide) 28.dp else 24.dp))
}

@Composable
private fun SshDetails(state: SshWizardUiState, onProbe: (String, String, String, String) -> Unit) {
    var host by rememberSaveable { mutableStateOf(state.request?.endpoint?.host ?: "") }
    var port by rememberSaveable { mutableStateOf(state.request?.endpoint?.port?.toString() ?: "22") }
    var domain by rememberSaveable { mutableStateOf(state.request?.domain ?: "") }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth().testTag("ssh-details"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (LocalDensity.current.fontScale <= 1.2f) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SshField(host, { host = it }, "IP-адрес или имя VPS", "ssh-host", state.busy, KeyboardType.Uri, Modifier.weight(1f), "203.0.113.10")
            SshField(port, { port = it }, "SSH-порт", "ssh-port", state.busy, KeyboardType.Number, Modifier.width(100.dp), "22")
        } else {
            SshField(host, { host = it }, "IP-адрес или имя VPS", "ssh-host", state.busy, KeyboardType.Uri, placeholder = "203.0.113.10")
            SshField(port, { port = it }, "SSH-порт", "ssh-port", state.busy, KeyboardType.Number, placeholder = "22")
        }
        SshField(domain, { domain = it }, "Домен будущего MeetSpace", "ssh-domain", state.busy, KeyboardType.Uri, placeholder = "meet.example.org")
        SshNote("Для VPS укажите IP или имя сервера. Для MeetSpace — домен или HTTPS-адрес.")
        SshButton("Проверить ключ сервера", {
            keyboard?.hide()
            domain = runCatching { SshInputValidator.normalizeDomain(domain) }.getOrDefault(domain)
            // Inspect the public host key before USERAUTH; credentials are entered after trust.
            onProbe(host, port, state.request?.endpoint?.user ?: "root", domain)
        }, !state.busy, "ssh-probe")
        SshNote("На этом шаге пароль и приватный ключ не передаются. Сначала подтвердите публичный ключ сервера по независимому источнику.")
    }
}

@Composable
private fun SshField(value: String, onValue: (String) -> Unit, label: String, tag: String, busy: Boolean,
    keyboard: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier, placeholder: String = "",
    secret: Boolean = false, visible: Boolean = false, onToggle: (() -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
        BasicTextField(value, onValue, enabled = !busy, singleLine = true, interactionSource = interaction,
            visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, lineHeight = 24.sp, color = colors.onSurface.copy(alpha = if (busy) .5f else 1f), fontWeight = FontWeight.Normal),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false, imeAction = if (secret) ImeAction.Done else ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label }
                .background(colors.surface, RoundedCornerShape(12.dp)).border(1.dp, if (focused) colors.primary else colors.outlineVariant, RoundedCornerShape(12.dp)),
            decorationBox = { inner ->
                Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(start = 14.dp, end = if (secret) 4.dp else 14.dp, top = 12.dp, bottom = 12.dp)) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, fontSize = 16.sp, lineHeight = 24.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.Normal)
                        inner()
                    }
                    if (onToggle != null) IconButton(onClick = onToggle, enabled = !busy, modifier = Modifier.size(48.dp)) {
                        Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (visible) "Скрыть данные доступа" else "Показать данные доступа", modifier = Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    }
                }
            })
    }
}

@Composable
private fun SshHostVerification(state: SshWizardUiState, onConfirm: () -> Unit, onForget: () -> Unit) {
    val identity = state.identity ?: return
    var compared by remember(identity.key.fingerprint) { mutableStateOf(false) }
    var forgetDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("ssh-host-verification"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SshEndpointLabel(identity.endpoint)
        if (identity.trust == SshTrust.CHANGED) {
            SshAlert("Ключ сервера изменился. Подключение заблокировано.", error = true, modifier = Modifier.testTag("ssh-host-changed").semantics { liveRegion = LiveRegionMode.Assertive })
            SshNote("Это может быть переустановка VPS или другой сервер. Не вводите данные доступа, пока не проверите причину через панель провайдера или администратора.")
            identity.previous?.let { SshFingerprint("Сохранённый отпечаток", it, "ssh-old-fingerprint") }
            SshFingerprint("Новый отпечаток", identity.key, "ssh-new-fingerprint")
            SshButton("Удалить сохранённое доверие…", { forgetDialog = true }, !state.busy, "ssh-forget-trust", primary = false)
        } else {
            SshSectionTitle("Первое подключение")
            SshFingerprint("SHA-256 отпечаток публичного ключа", identity.key, "ssh-new-fingerprint")
            SshNote("Сравните отпечаток с панелью VPS или данными администратора по независимому каналу. Само SSH-подключение не доказывает, что это нужный сервер.")
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(compared, enabled = !state.busy,
                role = Role.Checkbox, onClick = { compared = !compared }).testTag("ssh-fingerprint-compared").padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Checkbox(compared, onCheckedChange = null, enabled = !state.busy, modifier = Modifier.size(20.dp))
                Text("Я сравнил отпечаток с панелью VPS или данными администратора", Modifier.weight(1f), fontSize = 14.sp, lineHeight = 23.1.sp)
            }
            SshButton("Доверять этому ключу", onConfirm, compared && !state.busy, "ssh-trust")
        }
    }
    if (forgetDialog) AlertDialog(
        onDismissRequest = { forgetDialog = false }, title = { Text("Удалить доверие к серверу?") },
        text = { Text("Защита от подмены для ${identity.endpoint.host}:${identity.endpoint.port} будет сброшена. Затем потребуется заново получить ключ и сравнить отпечаток по независимому источнику. Продолжайте только после выяснения причины смены ключа.") },
        confirmButton = { TextButton(onClick = { forgetDialog = false; compared = false; onForget() }, modifier = Modifier.heightIn(min = 48.dp).testTag("ssh-forget-confirm")) { Text("Удалить доверие") } },
        dismissButton = { TextButton(onClick = { forgetDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Отмена") } },
    )
}

@Composable
private fun SshEndpointLabel(endpoint: SshEndpoint) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionContainer { Text(endpoint.host, fontFamily = FontFamily.Monospace, fontSize = if (LocalOnboardingWide.current) 15.sp else 16.sp, lineHeight = if (LocalOnboardingWide.current) 24.75.sp else 26.4.sp) }
        SshNote("SSH-порт ${endpoint.port}")
    }
}

@Composable
private fun SshFingerprint(title: String, key: SshHostKey, tag: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, fontFamily = FontFamily.Monospace, fontSize = 18.sp, lineHeight = 25.2.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-.25).sp, modifier = Modifier.semantics { heading() })
            Text(key.algorithm, fontFamily = FontFamily.Monospace, fontSize = if (LocalOnboardingWide.current) 13.sp else 14.sp,
                lineHeight = if (LocalOnboardingWide.current) 22.1.sp else 23.8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SelectionContainer { Text(key.fingerprint, fontFamily = FontFamily.Monospace, fontSize = if (LocalOnboardingWide.current) 13.sp else 14.sp,
                lineHeight = if (LocalOnboardingWide.current) 22.1.sp else 23.8.sp, modifier = Modifier.testTag(tag)) }
        }
    }
}

@Composable
private fun SshAuth(state: SshWizardUiState, onImport: (Uri) -> Unit, onClearKey: () -> Unit, onPassword: (String, String) -> Unit, onKey: (String, String) -> Unit, onReuseAccess: () -> Unit) {
    var user by rememberSaveable(state.request?.endpoint?.pinId) { mutableStateOf(state.request?.endpoint?.user ?: "root") }
    var keyMode by rememberSaveable { mutableStateOf(false) }
    var password by remember(state.credentialReset) { mutableStateOf("") }
    var passphrase by remember(state.credentialReset) { mutableStateOf("") }
    var visible by remember(state.credentialReset) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onImport(uri) }
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) { onDispose { password = ""; passphrase = "" } }
    Column(Modifier.fillMaxWidth().testTag("ssh-auth")) {
        state.request?.endpoint?.let { SshEndpointLabel(it); Spacer(Modifier.height(20.dp)) }
        state.identity?.let { SshFingerprint("Проверенный ключ сервера", it.key, "ssh-trusted-fingerprint"); Spacer(Modifier.height(24.dp)) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state.accessAvailable) {
                SshAlert("Пользователь: ${state.request?.endpoint?.user}\nДанные доступа сохранены в памяти открытого мастера.", success = true, modifier = Modifier.testTag("ssh-access-retained"))
                TextButton(onClick = onClearKey, enabled = !state.busy, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("ssh-change-access")) { Text("Изменить данные доступа") }
                SshButton("Проверить сервер без изменений", onReuseAccess, !state.busy, "ssh-preflight")
            } else {
                SshField(user, { user = it }, "Пользователь SSH", "ssh-user", state.busy, placeholder = "root")
                SshNote("Учётная запись SSH может отличаться от аккаунта MeetSpace.")
                Row(Modifier.fillMaxWidth().selectableGroup().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(false to "Пароль SSH", true to "Приватный ключ").forEach { (mode, title) ->
                        Row(Modifier.weight(1f).heightIn(min = 44.dp)
                            .background(if (keyMode == mode) MaterialTheme.colorScheme.surface else Color.Transparent, RoundedCornerShape(10.dp))
                            .selectable(keyMode == mode, enabled = !state.busy, role = Role.RadioButton,
                                onClick = { keyMode = mode; password = ""; passphrase = ""; visible = false; onClearKey() })
                            .padding(horizontal = 10.dp, vertical = 10.dp).testTag(if (mode) "ssh-method-key" else "ssh-method-password"),
                            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                            val tint = if (keyMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            Icon(if (mode) Icons.Outlined.Key else Icons.Outlined.Lock, null, Modifier.size(18.dp), tint = tint)
                            Text(title, fontSize = 14.sp, lineHeight = 21.sp, color = tint, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                if (keyMode) {
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy, shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface, containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp).testTag("ssh-import-key")) {
                        Icon(Icons.Outlined.Key, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (state.keyLoaded) "Выбрать другой ключ" else "Выбрать файл ключа", fontSize = if (LocalOnboardingWide.current) 15.sp else 16.sp)
                            SshNote("До 256 КиБ")
                        }
                    }
                    SshNote(if (state.keyLoaded) "Ключ загружен в память проверки" else "OpenSSH v1: без пароля или bcrypt до 64 раундов. RSA/EC PEM: только без шифрования. Зашифрованные PEM, PKCS#8 и PuTTY не поддерживаются.", Modifier.testTag("ssh-key-status"))
                    if (state.keyLoaded) TextButton(onClick = onClearKey, enabled = !state.busy, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("ssh-clear-key")) { Text("Удалить ключ из памяти") }
                    SshField(passphrase, { passphrase = it }, "Passphrase ключа (если есть)", "ssh-passphrase", state.busy, KeyboardType.Password,
                        placeholder = "Необязательно", secret = true, visible = visible, onToggle = { visible = !visible })
                } else {
                    SshField(password, { password = it }, "Пароль SSH", "ssh-password", state.busy, KeyboardType.Password,
                        secret = true, visible = visible, onToggle = { visible = !visible })
                }
                SshButton("Проверить сервер без изменений", {
                    keyboard?.hide()
                    if (keyMode) { val value = passphrase; passphrase = ""; onKey(user, value) }
                    else { val value = password; password = ""; onPassword(user, value) }
                    visible = false
                }, !state.busy && user.isNotBlank() && if (keyMode) state.keyLoaded else password.isNotEmpty(), "ssh-preflight")
            }
            SshNote("SSH используется только для диагностики. Пароль не передаётся в sudo. Изменение firewall, пакетов и Docker на этом этапе не выполняется.")
        }
    }
}

@Composable
private fun SshResults(state: SshWizardUiState, onInstall: (() -> Unit)?, onRepeat: () -> Unit) {
    val report = state.report ?: return
    Column(Modifier.fillMaxWidth().testTag("ssh-results"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SshEndpointLabel(report.endpoint)
        onInstall?.let { SshButton("Подготовить план установки", it, !state.busy, "ssh-install") }
        SshAlert("План установки ещё не создан. Изменения на сервере не выполнялись.", modifier = Modifier.testTag("ssh-no-install"))
        if (report.hasBlockers) SshAlert("Обнаружены препятствия. Устраните их и повторите проверку перед установкой.", error = true)
        else SshNote("Предупреждения и неизвестные параметры требуют внимания перед установкой.")
        SshCard {
            SshSectionTitle("Проверка сервера")
            Spacer(Modifier.height(14.dp))
            report.checks.forEachIndexed { index, check ->
                if (index > 0) { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); Spacer(Modifier.height(16.dp)) }
                SshCheckRow(check)
                if (index != report.checks.lastIndex) Spacer(Modifier.height(16.dp))
            }
        }
        SshButton("Повторить проверку", onRepeat, !state.busy, "ssh-repeat", primary = false)
    }
}

@Composable
private fun SshCheckRow(check: PreflightCheck) {
    val wide = LocalConfiguration.current.screenWidthDp > 1000 && LocalDensity.current.fontScale <= 1.2f
    Row(Modifier.fillMaxWidth().testTag("ssh-check-${check.id}"), horizontalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 10.dp), verticalAlignment = Alignment.Top) {
        Icon(if (check.level == PreflightLevel.PASS) Icons.Outlined.CheckCircleOutline else Icons.Outlined.Info, null,
            Modifier.padding(top = 2.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(check.title, fontSize = if (LocalOnboardingWide.current) 15.sp else 16.sp, lineHeight = if (LocalOnboardingWide.current) 22.5.sp else 24.sp, fontWeight = FontWeight.SemiBold)
            SshNote(check.detail)
            if (!wide) { Spacer(Modifier.height(5.dp)); SshCheckStatus(check.level) }
        }
        if (wide) SshCheckStatus(check.level)
    }
}

@Composable
private fun SshCheckStatus(level: PreflightLevel) {
    val color = when (level) {
        PreflightLevel.PASS -> onboardingTone(0xFF23785E, 0xFF95DEBF)
        PreflightLevel.WARN -> onboardingTone(0xFF90651B, 0xFFE8C27B)
        PreflightLevel.BLOCKED -> onboardingTone(0xFFB34250, 0xFFFFB2BE)
        PreflightLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val background = when (level) {
        PreflightLevel.PASS -> onboardingTone(0xFFEDF8F3, 0xFF273B35)
        PreflightLevel.WARN -> onboardingTone(0xFFFFF7E6, 0xFF3A3227)
        PreflightLevel.BLOCKED -> onboardingTone(0xFFFFF0F2, 0xFF422B35)
        PreflightLevel.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(shape = RoundedCornerShape(7.dp), color = background) {
        Text(when (level) { PreflightLevel.PASS -> "PASS · пройдено"; PreflightLevel.WARN -> "WARN · внимание"; PreflightLevel.BLOCKED -> "BLOCKED · препятствие"; PreflightLevel.UNKNOWN -> "UNKNOWN · неизвестно" },
            Modifier.widthIn(max = if (LocalOnboardingWide.current) 150.dp else 320.dp).padding(horizontal = 9.dp, vertical = 4.dp), fontSize = if (LocalOnboardingWide.current) 13.sp else 14.sp,
            lineHeight = if (LocalOnboardingWide.current) 18.2.sp else 19.6.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun SshSectionTitle(text: String) {
    Text(text, fontSize = 18.sp, lineHeight = 25.2.sp, letterSpacing = (-.25).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
}

@Composable
private fun SshNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = if (LocalOnboardingWide.current) 13.sp else 14.sp,
        lineHeight = if (LocalOnboardingWide.current) 20.8.sp else 22.4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SshCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(if (LocalOnboardingWide.current) 20.dp else 18.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(if (LocalOnboardingWide.current) 24.dp else 20.dp), content = content)
    }
}

@Composable
private fun SshButton(text: String, onClick: () -> Unit, enabled: Boolean = true, tag: String? = null, primary: Boolean = true) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier)
    val label: @Composable RowScope.() -> Unit = { Text(text, fontSize = 15.sp, lineHeight = 22.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp) }
    if (primary) Button(onClick, enabled = enabled, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = .45f).compositeOver(MaterialTheme.colorScheme.background),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = .45f).compositeOver(MaterialTheme.colorScheme.background),
        ), modifier = modifier, content = label)
    else OutlinedButton(onClick, enabled = enabled, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface, containerColor = MaterialTheme.colorScheme.surface), modifier = modifier, content = label)
}

@Composable
private fun SshAlert(message: String, error: Boolean = false, success: Boolean = false, modifier: Modifier = Modifier) {
    val color = if (error) onboardingTone(0xFFB34250, 0xFFFFB2BE) else if (success) onboardingTone(0xFF23785E, 0xFF95DEBF) else MaterialTheme.colorScheme.onSurfaceVariant
    val background = if (error) onboardingTone(0xFFFFF0F2, 0xFF422B35) else if (success) onboardingTone(0xFFEDF8F3, 0xFF273B35) else MaterialTheme.colorScheme.surfaceVariant
    val border = if (error) onboardingTone(0xFFEDC5CC, 0xFF68404C) else if (success) onboardingTone(0xFFC2E4D5, 0xFF3C5B4F) else MaterialTheme.colorScheme.outlineVariant
    Surface(modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, color = background, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, border)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(if (error) Icons.Outlined.ErrorOutline else if (success) Icons.Outlined.CheckCircleOutline else Icons.Outlined.Info, null, Modifier.padding(top = 2.dp).size(20.dp), tint = color)
            Text(message, Modifier.weight(1f), fontSize = if (LocalOnboardingWide.current) 13.sp else 14.sp, lineHeight = if (LocalOnboardingWide.current) 21.45.sp else 23.1.sp, color = color)
        }
    }
}

@Composable
private fun SshMessage(message: String, error: Boolean, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(if (error) "ssh-error" else "ssh-notice").semantics { liveRegion = LiveRegionMode.Polite }) {
        SshAlert(message, error)
        TextButton(onClick = onClear, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("Закрыть сообщение") }
    }
}
