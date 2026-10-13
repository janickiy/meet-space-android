package com.meetspace.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetspace.app.SshWizardStep
import com.meetspace.app.SshWizardUiState
import com.meetspace.core.designsystem.MeetSpaceTokens
import com.meetspace.core.model.*

@Composable
fun SshWizardUi(
    state: SshWizardUiState,
    onProbe: (String, String, String, String) -> Unit,
    onConfirmTrust: () -> Unit,
    onForgetTrust: () -> Unit,
    onImportKey: (Uri) -> Unit,
    onClearKey: () -> Unit,
    onPasswordPreflight: (String) -> Unit,
    onKeyPreflight: (String) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    onExit: () -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier,
    onInstall: (() -> Unit)? = null,
    onReconnectInstall: (() -> Unit)? = null,
) {
    val back = { if (state.busy) onCancel() else if (state.step == SshWizardStep.DETAILS) onExit() else onBack() }
    BackHandler(onBack = back)
    Scaffold(modifier.testTag("ssh-wizard"), contentWindowInsets = WindowInsets.safeDrawing, containerColor = MaterialTheme.colorScheme.surface) { insets ->
        IntroFrame(message = listOf(state.step.name, state.error, state.notice).joinToString(), modifier = Modifier.padding(insets).imePadding(), install = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = back, modifier = Modifier.heightIn(min = 48.dp).testTag("ssh-back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text(if (state.busy) "Отменить" else "Назад")
                    }
                    IconButton(onClick = onExit, modifier = Modifier.size(48.dp).testTag("ssh-exit")) {
                        Icon(Icons.Outlined.Close, contentDescription = "Закрыть SSH-проверку")
                    }
                }
                OnboardingProgress(state.step.ordinal + 1)
                Text(when (state.step) { SshWizardStep.DETAILS -> "СОБСТВЕННАЯ ИНФРАСТРУКТУРА"; SshWizardStep.VERIFY_HOST -> "ИДЕНТИЧНОСТЬ СЕРВЕРА"; SshWizardStep.AUTH -> "ДОСТУП ПО SSH"; SshWizardStep.RESULTS -> "ПЕРЕД УСТАНОВКОЙ" }, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when (state.step) { SshWizardStep.DETAILS -> "Начнём с сервера."; SshWizardStep.VERIFY_HOST -> "Знакомимся\nбезопасно."; SshWizardStep.AUTH -> "Доступ к серверу."; SshWizardStep.RESULTS -> if (state.report?.hasBlockers == true) "Проверим ограничения." else "Всё по плану." }, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                Text(when (state.step) { SshWizardStep.DETAILS -> "Нужен отдельный удалённый Linux VPS с публичным IP-адресом."; SshWizardStep.VERIFY_HOST -> "При первом подключении проверьте отпечаток SSH-ключа сервера."; SshWizardStep.AUTH -> "Подтвердите доступ для проверки сервера перед установкой."; SshWizardStep.RESULTS -> "Ознакомьтесь с результатами диагностики сервера." }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { SshMessage(it, true, onClearMessage) }
                state.notice?.let { SshMessage(it, false, onClearMessage) }
                onReconnectInstall?.let { reconnect -> OutlinedButton(shape = RoundedCornerShape(10.dp), onClick = reconnect, enabled = !state.busy) { Text("Восстановить наблюдение за установкой") } }
                if (state.step == SshWizardStep.RESULTS && onInstall != null) {
                    Button(shape = RoundedCornerShape(10.dp), onClick = onInstall, enabled = !state.busy, modifier = Modifier.testTag("ssh-install")) { Text("Подготовить план установки") }
                }
                if (state.busy) {
                    Card(Modifier.fillMaxWidth().testTag("ssh-progress"), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = onboardingTone(0xFFF6F8FC, 0xFF252F40))) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(state.operation ?: "Проверка…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            OutlinedButton(shape = RoundedCornerShape(10.dp), onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("ssh-cancel")) { Text("Отменить проверку") }
                        }
                    }
                }
                when (state.step) {
                    SshWizardStep.DETAILS -> SshDetails(state, onProbe)
                    SshWizardStep.VERIFY_HOST -> SshHostVerification(state, onConfirmTrust, onForgetTrust)
                    SshWizardStep.AUTH -> SshAuth(state, onImportKey, onClearKey, onPasswordPreflight, onKeyPreflight)
                    SshWizardStep.RESULTS -> SshResults(state)
                }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Пароль и приватный ключ используются в памяти одной проверки. Они не сохраняются в приложении.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
        }
    }
}

@Composable
private fun SshDetails(state: SshWizardUiState, onProbe: (String, String, String, String) -> Unit) {
    var host by rememberSaveable { mutableStateOf(state.request?.endpoint?.host ?: "") }
    var port by rememberSaveable { mutableStateOf(state.request?.endpoint?.port?.toString() ?: "22") }
    var user by rememberSaveable { mutableStateOf(state.request?.endpoint?.user ?: "root") }
    var domain by rememberSaveable { mutableStateOf(state.request?.domain ?: "") }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.testTag("ssh-details"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (LocalDensity.current.fontScale <= 1.2f) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SshField(host, { host = it }, "IP-адрес или имя VPS", "ssh-host", state.busy, KeyboardType.Uri, Modifier.weight(1f))
            SshField(port, { port = it }, "SSH-порт", "ssh-port", state.busy, KeyboardType.Number, Modifier.width(85.dp))
        } else {
            SshField(host, { host = it }, "IP-адрес или имя VPS", "ssh-host", state.busy, KeyboardType.Uri)
            SshField(port, { port = it }, "SSH-порт", "ssh-port", state.busy, KeyboardType.Number)
        }
        SshField(user, { user = it }, "Пользователь SSH", "ssh-user", state.busy)
        SshField(domain, { domain = it }, "Домен будущего MeetSpace", "ssh-domain", state.busy, KeyboardType.Uri)
        Text("Укажите только имя или IP без https://, пути и команд. Пользователь SSH может отличаться от аккаунта MeetSpace.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Button(shape = RoundedCornerShape(10.dp), onClick = { keyboard?.hide(); onProbe(host, port, user, domain) }, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp).testTag("ssh-probe")) { Text("Проверить ключ сервера") }
        Text("На этом шаге пароль и приватный ключ не передаются. Сначала подтвердите публичный ключ сервера по независимому источнику.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SshField(value: String, onValue: (String) -> Unit, label: String, tag: String, busy: Boolean, keyboard: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onValue, label = { Text(label, fontSize = 11.sp) }, singleLine = true, enabled = !busy,
        shape = RoundedCornerShape(10.dp), colors = onboardingFieldColors(), textStyle = onboardingInputStyle(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = modifier.fillMaxWidth().testTag(tag))
}

@Composable
private fun SshHostVerification(state: SshWizardUiState, onConfirm: () -> Unit, onForget: () -> Unit) {
    val identity = state.identity ?: return
    var compared by remember(identity.key.fingerprint) { mutableStateOf(false) }
    var forgetDialog by remember { mutableStateOf(false) }
    Column(Modifier.testTag("ssh-host-verification"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("${identity.endpoint.host}:${identity.endpoint.port}", style = MaterialTheme.typography.titleLarge)
        if (identity.trust == SshTrust.CHANGED) {
            Text("Ключ сервера изменился. Подключение заблокировано.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("ssh-host-changed").semantics { liveRegion = LiveRegionMode.Assertive })
            Text("Это может быть переустановка VPS или другой сервер. Не вводите данные доступа, пока не проверите причину через панель провайдера или администратора.")
            identity.previous?.let { SshFingerprint("Сохранённый отпечаток", it, "ssh-old-fingerprint") }
            SshFingerprint("Новый отпечаток", identity.key, "ssh-new-fingerprint")
            OutlinedButton(shape = RoundedCornerShape(10.dp), onClick = { forgetDialog = true }, enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("ssh-forget-trust")) { Text("Удалить сохранённое доверие…") }
        } else {
            Text("Первое подключение", style = MaterialTheme.typography.titleLarge)
            SshFingerprint("SHA-256 отпечаток публичного ключа", identity.key, "ssh-new-fingerprint")
            Text("Сравните отпечаток с данными в панели VPS или узнайте его у администратора по независимому каналу. Само SSH-подключение не доказывает, что это нужный сервер.")
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(compared, enabled = !state.busy,
                role = Role.Checkbox, onClick = { compared = !compared }).testTag("ssh-fingerprint-compared"), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(compared, onCheckedChange = null, enabled = !state.busy)
                Spacer(Modifier.width(12.dp)); Text("Я сравнил отпечаток с панелью VPS или данными администратора")
            }
            Button(shape = RoundedCornerShape(10.dp), onClick = onConfirm, enabled = compared && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp).testTag("ssh-trust")) {
                Text("Доверять этому ключу")
            }
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
private fun SshFingerprint(title: String, key: SshHostKey, tag: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().background(onboardingTone(0xFFF7F9FC, 0xFF252F40)).padding(horizontal = 15.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Dns, null, Modifier.size(16.dp), tint = onboardingTone(0xFF8A98AE, 0xFFA5B3C9))
                Text(title, Modifier.weight(1f), fontSize = 11.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text(key.algorithm, fontSize = 9.sp, letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(key.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 21.sp, color = onboardingTone(0xFF6B7892, 0xFFBAC8DF), modifier = Modifier.testTag(tag)) }
            }
        }
    }
}

@Composable
private fun SshAuth(state: SshWizardUiState, onImport: (Uri) -> Unit, onClearKey: () -> Unit, onPassword: (String) -> Unit, onKey: (String) -> Unit) {
    var keyMode by rememberSaveable { mutableStateOf(false) }
    var password by remember(state.credentialReset) { mutableStateOf("") }
    var passphrase by remember(state.credentialReset) { mutableStateOf("") }
    var visible by remember(state.credentialReset) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onImport(uri) }
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) { onDispose { password = ""; passphrase = "" } }
    Column(Modifier.testTag("ssh-auth"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Доступ к ${state.request?.endpoint?.host}", style = MaterialTheme.typography.titleLarge)
        Text("Пользователь: ${state.request?.endpoint?.user} · порт ${state.request?.endpoint?.port}")
        state.identity?.let { SshFingerprint("Проверенный ключ сервера", it.key, "ssh-trusted-fingerprint") }
        Row(Modifier.fillMaxWidth().selectableGroup().background(onboardingTone(0xFFF0F3F8, 0xFF202A39), RoundedCornerShape(10.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(false to "Пароль SSH", true to "Приватный ключ").forEach { (mode, title) ->
                Row(Modifier.weight(1f).heightIn(min = 48.dp).background(if (keyMode == mode) onboardingTone(0xFFFFFFFF, 0xFF384963) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(7.dp)).selectable(keyMode == mode, enabled = !state.busy, role = Role.RadioButton,
                    onClick = { keyMode = mode; password = ""; passphrase = ""; visible = false; onClearKey() }).testTag(if (mode) "ssh-method-key" else "ssh-method-password"), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(8.dp))
                    Icon(if (mode) Icons.Outlined.Key else Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = if (keyMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(7.dp)); Text(title, fontSize = 11.sp, lineHeight = 17.sp, color = if (keyMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (keyMode) {
            OutlinedButton(shape = RoundedCornerShape(10.dp), onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp).testTag("ssh-import-key")) { Text(if (state.keyLoaded) "Выбрать другой ключ" else "Выбрать файл ключа") }
            Text(if (state.keyLoaded) "Ключ загружен в память проверки" else "OpenSSH v1: без пароля или bcrypt до 64 раундов. RSA/EC PEM: только без шифрования. Зашифрованные PEM, PKCS#8 и PuTTY не поддерживаются. Максимум 256 КиБ; файл остаётся только в памяти.", modifier = Modifier.testTag("ssh-key-status"), style = MaterialTheme.typography.bodyMedium)
            if (state.keyLoaded) TextButton(onClick = onClearKey, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("ssh-clear-key")) { Text("Удалить ключ из памяти") }
            SshSecretField(passphrase, { passphrase = it }, "Passphrase ключа (если есть)", "ssh-passphrase", visible, { visible = !visible }, state.busy)
        } else {
            SshSecretField(password, { password = it }, "Пароль SSH", "ssh-password", visible, { visible = !visible }, state.busy)
        }
        Button(shape = RoundedCornerShape(10.dp), onClick = {
            keyboard?.hide()
            if (keyMode) { val value = passphrase; passphrase = ""; onKey(value) }
            else { val value = password; password = ""; onPassword(value) }
            visible = false
        }, enabled = !state.busy && if (keyMode) state.keyLoaded else password.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp).testTag("ssh-preflight")) { Text("Проверить сервер без изменений") }
        Text("SSH используется только для диагностики. Пароль не передаётся в sudo. Установка, изменение firewall, пакетов и Docker на этом этапе не выполняются.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SshSecretField(value: String, onValue: (String) -> Unit, label: String, tag: String, visible: Boolean, onToggle: () -> Unit, busy: Boolean) {
    OutlinedTextField(value, onValue, label = { Text(label, fontSize = 11.sp) }, enabled = !busy, singleLine = true,
        shape = RoundedCornerShape(10.dp), colors = onboardingFieldColors(), textStyle = onboardingInputStyle(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        trailingIcon = { IconButton(onClick = onToggle, enabled = !busy, modifier = Modifier.size(48.dp)) {
            Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (visible) "Скрыть данные доступа" else "Показать данные доступа")
        } }, modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
private fun SshResults(state: SshWizardUiState) {
    val report = state.report ?: return
    Column(Modifier.testTag("ssh-results"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Проверка завершена", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text("${report.endpoint.host}:${report.endpoint.port} · ${report.domain}")
        Text(if (report.hasBlockers) "Обнаружены препятствия. Перед будущей установкой их нужно устранить и повторить проверку." else "Результат диагностики готов. Предупреждения и неизвестные параметры требуют внимания перед будущей установкой.", style = MaterialTheme.typography.bodyLarge)
        Text("План установки ещё не создан. Изменения на сервере не выполнялись.", color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("ssh-no-install"))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, onboardingTone(0xFFE4E9F1, 0xFF354157))) {
        Column {
        Text("Проверка сервера", Modifier.fillMaxWidth().background(onboardingTone(0xFFF9FAFC, 0xFF252F40)).padding(horizontal = 15.dp, vertical = 13.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        report.checks.forEach { check ->
            val color = when (check.level) {
                PreflightLevel.PASS -> MaterialTheme.colorScheme.tertiary
                PreflightLevel.WARN -> MaterialTheme.colorScheme.secondary
                PreflightLevel.BLOCKED -> MaterialTheme.colorScheme.error
                PreflightLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val label = when (check.level) { PreflightLevel.PASS -> "PASS · пройдено"; PreflightLevel.WARN -> "WARN · внимание"; PreflightLevel.BLOCKED -> "BLOCKED · препятствие"; PreflightLevel.UNKNOWN -> "UNKNOWN · неизвестно" }
            HorizontalDivider(color = onboardingTone(0xFFEDF0F5, 0xFF354157))
            Row(Modifier.fillMaxWidth().padding(15.dp).testTag("ssh-check-${check.id}"), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(8.dp), color = onboardingTone(0xFFF7F9FC, 0xFF2D3A50), border = BorderStroke(1.dp, onboardingTone(0xFFE9EDF4, 0xFF42516B)), modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Outlined.Dns, null, Modifier.padding(7.dp), tint = onboardingTone(0xFF8D9AB2, 0xFFB1C3E0))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(check.title, fontSize = 11.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(check.detail, fontSize = 10.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(label, fontSize = 9.sp, lineHeight = 15.sp, color = color)
                }
                Icon(if (check.level == PreflightLevel.PASS) Icons.Outlined.CheckCircleOutline else Icons.Outlined.ErrorOutline, null, Modifier.size(17.dp), tint = color)
            }
        }
        }
        }
    }
}

@Composable
private fun SshMessage(message: String, error: Boolean, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(if (error) "ssh-error" else "ssh-notice").semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(message, color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text("Закрыть сообщение") }
        }
    }
}
