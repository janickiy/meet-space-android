package com.meetspace.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meetspace.app.*
import com.meetspace.core.model.*
import com.meetspace.core.ssh.installPhases

@Composable
fun InstallerUi(
    state: InstallerUiState, onImportKey: (Uri) -> Unit,
    onAuthenticate: (InstallOptions, String, Boolean) -> Unit,
    onApply: (Boolean, Boolean, Boolean) -> Unit, onControl: (String) -> Unit,
    onDetach: () -> Unit, onReconnect: () -> Unit, onConnect: (String) -> Unit, onForgetCompleted: () -> Unit = {}, onRefreshPlan: () -> Unit = {},
    onCreateAccount: ((String) -> Unit)? = null,
) {
    BackHandler(onBack = onDetach)
    var detailsExpanded by remember(state.ticket?.jobId) { mutableStateOf(false) }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, containerColor = MaterialTheme.colorScheme.surface) { padding ->
        IntroFrame(message = listOf(state.screen.name, state.error).joinToString(), modifier = Modifier.padding(padding).imePadding().testTag("installer"), install = true) {
                Text(when (state.status?.state) { "completed" -> "Пространство готово."; "failed", "needs_recovery" -> "Продолжим с этого места."; else -> "Установка MeetSpace" }, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                OnboardingProgress(if (state.status?.state == "completed") 5 else 4)
                Text("${state.endpoint?.user}@${state.endpoint?.host}:${state.endpoint?.port}", style = if (state.screen == InstallScreen.PROGRESS) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
                    color = if (state.screen == InstallScreen.PROGRESS) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (state.screen != InstallScreen.PROGRESS) Text("Закрытие экрана или обрыв SSH не останавливают серверное задание. Доступ хранится только в памяти открытого мастера.")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("install-error")) }
                if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Проверка ответа сервера…") }
                if (state.screen == InstallScreen.ACCESS) {
                    InstallAccess(state, onImportKey, onAuthenticate)
                } else if (state.needsAccess && state.status?.state != "completed" && state.screen != InstallScreen.PROGRESS) {
                    OutlinedButton(shape = RoundedCornerShape(10.dp), onClick = onReconnect, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("install-reconnect")) { Text("Подключиться снова") }
                }
                if (state.screen == InstallScreen.PLAN) state.plan?.let { plan ->
                    InstallPlanCard(plan)
                    InstallConsent(plan, state.busy || state.needsAccess, onApply)
                }
                if (state.screen == InstallScreen.PROGRESS) {
                    val status = state.status
                    if (status == null) {
                        Text("Состояние пока неизвестно. Для проверки подключитесь снова.")
                        if (state.needsAccess) OutlinedButton(shape = RoundedCornerShape(10.dp), onClick = onReconnect, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("install-reconnect")) { Text("Подключиться снова") }
                    } else {
                        InstallationStatusCard(state, status)
                        status.errorCode?.let { Text(installErrorMessage(it), color = MaterialTheme.colorScheme.error) }
                        if (state.needsAccess && status.state != "completed") OutlinedButton(shape = RoundedCornerShape(10.dp), onClick = onReconnect, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("install-reconnect")) { Text("Подключиться снова") }
                        if (status.state == "completed") {
                            val domain = (state.plan?.options?.domain ?: state.ticket?.plan?.options?.domain)?.takeIf { it.isNotBlank() }
                            val origin = domain?.let { "https://$it" }
                            if (onCreateAccount != null) {
                                Button(shape = RoundedCornerShape(10.dp), onClick = { origin?.let(onCreateAccount) },
                                    enabled = !state.busy && origin != null,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp).testTag("install-register")) { Text("Создать аккаунт") }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Уже есть аккаунт?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = { origin?.let(onConnect) }, enabled = !state.busy && origin != null,
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("install-login")) { Text("Войти") }
                                }
                            } else {
                                Button(shape = RoundedCornerShape(10.dp), onClick = { origin?.let(onConnect) },
                                    enabled = !state.busy && origin != null,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp).testTag("install-connect")) { Text("Подключить MeetSpace") }
                            }
                            TextButton(onClick = onForgetCompleted, enabled = !state.busy) { Text("Убрать завершённое задание с устройства") }
                        } else if (!state.needsAccess) {
                            if (status.state in setOf("needs_recovery", "cancelled", "failed")) {
                                Text("Продолжение использует прежний план и сохранённые секреты. Миграции защищены журналом; данные и тома не удаляются.")
                                Button(shape = RoundedCornerShape(10.dp), onClick = { onControl("resume") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("install-resume")) { Text("Подтвердить продолжение") }
                            } else {
                                Text("Остановка произойдёт после текущей команды: сборка одного образа может занять до часа. Запущенные сервисы и данные сохранятся.")
                                OutlinedButton(shape = RoundedCornerShape(10.dp), onClick = { onControl("cancel") }, enabled = !state.busy && status.state != "cancel_requested", modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("install-cancel")) { Text("Остановить установку на сервере") }
                            }
                        }
                    }
                    TextButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("install-details-toggle").semantics { stateDescription = if (detailsExpanded) "Развернуто" else "Свернуто" }) {
                        Text("Подробности установки", Modifier.weight(1f))
                        Icon(Icons.Outlined.ExpandMore, null, Modifier.rotate(if (detailsExpanded) 180f else 0f))
                    }
                    if (detailsExpanded) Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.background,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().testTag("install-details")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SelectionContainer { Text("Задание: ${state.ticket?.jobId}\nCommit: ${state.plan?.commit}", style = MaterialTheme.typography.bodySmall) }
                            Text("Закрытие экрана или обрыв SSH не останавливают серверное задание. Доступ хранится только в памяти открытого мастера.", style = MaterialTheme.typography.bodySmall)
                            if (status?.state == "completed") {
                                Text("Сервисы, версии образов, HTTPS и авторизация TURN проверены с VPS. Доступ из вашей сети будет проверен при подключении. Видеозвонок ещё не проверялся.", style = MaterialTheme.typography.bodySmall)
                                Text("Последний шаг: ${phaseLabel(status.phase)} · завершено ${status.completedPhases.size} из ${installPhases.size}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (status != null) {
                                if (status.state in setOf("needs_recovery", "failed", "cancelled")) InstallationProgressSummary(state, status)
                                if (status.state in setOf("needs_recovery", "failed", "cancelled") || state.needsAccess && !status.terminal) {
                                    Text("Этапы установки", style = MaterialTheme.typography.titleMedium)
                                    InstallationPhaseChecklist(status, state.needsAccess && !status.terminal)
                                }
                                Text("Журнал этапов", style = MaterialTheme.typography.titleMedium)
                                status.events.takeLast(12).forEach { event -> Text("${event.sequence}. ${phaseLabel(event.phase)} · ${event.state}${event.errorCode?.let { " · $it" } ?: ""}",
                                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    if (state.errorCode == "STATE_UNAVAILABLE" && !state.needsAccess) {
                        OutlinedButton(shape = RoundedCornerShape(10.dp), onClick = onRefreshPlan, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Проверить сервер и обновить план") }
                        state.plan?.let { InstallConsent(it, state.busy, onApply) }
                    }
                }
        }
    }
}

/** Visual progress uses only acknowledged server phases; a detached connection is never shown as running. */
@Composable
private fun InstallationStatusCard(state: InstallerUiState, status: InstallStatus) {
    val stale = state.needsAccess && !status.terminal
    val complete = status.state == "completed"
    val recovery = status.state in setOf("needs_recovery", "failed", "cancelled")
    val statusText = if (stale) "Нет свежих данных о состоянии" else when (status.state) {
        "completed" -> "Установка на сервере завершена"
        "needs_recovery", "failed" -> "Требуется восстановление"
        "cancelled" -> "Остановлено на безопасной границе"
        "cancel_requested" -> "Остановка запрошена"
        else -> "Установка выполняется"
    }
    if (complete) {
        Box(Modifier.fillMaxWidth().height(115.dp), contentAlignment = Alignment.Center) {
            val orbitColor = onboardingTone(0xFFD6E7DC, 0xFF466D52)
            Canvas(Modifier.size(125.dp, 115.dp)) {
                drawCircle(orbitColor, 51.5.dp.toPx(), Offset(size.width / 2, size.height / 2), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
                drawCircle(androidx.compose.ui.graphics.Color(0xFFB6D4C1), 4.dp.toPx(), Offset(7.dp.toPx(), 75.dp.toPx()))
            }
            Surface(shape = RoundedCornerShape(23.dp), color = onboardingTone(0xFFECF6EE, 0xFF2F493E),
                border = BorderStroke(1.dp, onboardingTone(0xFFD3E9DA, 0xFF41634F)), modifier = Modifier.size(71.dp).rotate(-8f)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Check, null, tint = onboardingTone(0xFF6CA17B, 0xFFB3D4BC), modifier = Modifier.size(32.dp).rotate(8f)) }
            }
            Text("✦", Modifier.offset(x = 58.dp, y = (-33).dp), color = androidx.compose.ui.graphics.Color(0xFFB9AFE0), fontSize = 24.sp)
        }
    } else if (stale || recovery) {
        Surface(shape = RoundedCornerShape(16.dp), color = if (stale) MaterialTheme.colorScheme.surfaceVariant else onboardingTone(0xFFFFF7EC, 0xFF443D35),
            border = BorderStroke(1.dp, if (stale) MaterialTheme.colorScheme.outlineVariant else onboardingTone(0xFFF0E4D5, 0xFF6A5944)), modifier = Modifier.size(58.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(if (stale) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline, null,
                tint = if (stale) MaterialTheme.colorScheme.primary else onboardingTone(0xFFC3A272, 0xFFDABF96), modifier = Modifier.size(27.dp)) }
        }
    }
    Text(statusText, style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("install-status"))
    if (complete) {
        Surface(shape = RoundedCornerShape(12.dp), color = onboardingTone(0xFFFAFBFD, 0xFF252F40),
            border = BorderStroke(1.dp, onboardingTone(0xFFE6EBF3, 0xFF364359)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("АДРЕС ВАШЕГО ПРОСТРАНСТВА", fontSize = 10.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.plan?.options?.domain.orEmpty(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Outlined.CheckCircleOutline, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Text("HTTPS проверен с VPS", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    } else {
        if (stale || recovery) Surface(shape = RoundedCornerShape(12.dp), color = onboardingTone(0xFFFAF9F6, 0xFF363631),
            border = BorderStroke(1.dp, onboardingTone(0xFFEEEAE2, 0xFF505048)), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Icon(Icons.Outlined.History, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Последний известный этап", fontSize = 10.sp, color = onboardingTone(0xFFA59A87, 0xFFD4D1BE))
                    Text(phaseLabel(status.phase), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (stale) Text("Подключитесь снова, чтобы проверить состояние сервера.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!recovery) InstallationProgressSummary(state, status)
    }

    if (!complete && !recovery && !stale) InstallationPhaseChecklist(status, stale = false)
}

@Composable
private fun InstallationProgressSummary(state: InstallerUiState, status: InstallStatus) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(11.dp), color = onboardingTone(0xFFF4F7FD, 0xFF2D3A50), border = BorderStroke(1.dp, onboardingTone(0xFFDFE6F4, 0xFF42516B)), modifier = Modifier.size(43.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Dns, null, Modifier.size(23.dp), tint = onboardingTone(0xFF8498BA, 0xFFB1C3E0)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(state.plan?.options?.domain.orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(state.endpoint?.host.orEmpty(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${status.completedPhases.size * 100 / installPhases.size}%", fontSize = 27.sp, color = MaterialTheme.colorScheme.primary)
    }
    LinearProgressIndicator(progress = { status.completedPhases.size / installPhases.size.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp), trackColor = onboardingTone(0xFFECF0F7, 0xFF364156))
    Text("Последний шаг: ${phaseLabel(status.phase)} · завершено ${status.completedPhases.size} из ${installPhases.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InstallationPhaseChecklist(status: InstallStatus, stale: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        installPhases.forEachIndexed { index, phase ->
            val done = phase in status.completedPhases
            val current = !done && !status.terminal && !stale && phase == status.phase.substringBefore(':')
            Row(Modifier.fillMaxWidth().heightIn(min = 61.dp), horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, modifier = Modifier.size(29.dp), border = BorderStroke(1.dp, when { done -> onboardingTone(0xFFDCECDF, 0xFF405F50); current -> onboardingTone(0xFFDCE5FF, 0xFF435D88); else -> onboardingTone(0xFFE6EAF2, 0xFF3D4A61) }),
                    color = when { done -> onboardingTone(0xFFF0F7F2, 0xFF2F473F); current -> onboardingTone(0xFFEDF3FF, 0xFF2D405F); else -> onboardingTone(0xFFFFFFFF, 0xFF242F40) }) {
                    Box(contentAlignment = Alignment.Center) {
                        if (done) Icon(Icons.Outlined.Check, null, Modifier.size(13.dp), tint = onboardingTone(0xFF75A586, 0xFFA6CFB0))
                        else if (current) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        else Text((index + 1).toString().padStart(2, '0'), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(phaseLabel(phase), fontSize = 11.sp, lineHeight = 17.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(when { done -> "Завершено"; current -> if (status.state == "cancel_requested") "Остановка запрошена" else "Выполняется"; stale && phase == status.phase.substringBefore(':') -> "Состояние требует проверки"; else -> "Не подтверждено" },
                        fontSize = 9.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (current) Surface(shape = RoundedCornerShape(4.dp), color = onboardingTone(0xFFEFF4FF, 0xFF2D405F)) {
                    Text("Сейчас", Modifier.padding(horizontal = 6.dp, vertical = 4.dp), fontSize = 8.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun InstallAccess(state: InstallerUiState, onImport: (Uri) -> Unit, onAuthenticate: (InstallOptions, String, Boolean) -> Unit) {
    var domain by rememberSaveable(state.endpoint) { mutableStateOf(state.options.domain) }
    var ip by rememberSaveable(state.endpoint) { mutableStateOf(state.options.publicIpv4) }
    var useKey by rememberSaveable { mutableStateOf(false) }
    // Deliberately not rememberSaveable: no secret in SavedState, disk, UI logs or navigation args.
    var password by remember(state.credentialReset, useKey) { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onImport) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.ticket == null) {
            Text("Новый выделенный VPS: Ubuntu 22.04 или 24.04 LTS, amd64 или arm64. Минимум: 2 CPU, 4 ГБ RAM и около 20 ГБ свободного диска (18 ГиБ). Рекомендуется: 4 CPU, 8 ГБ RAM и 30 ГиБ свободно.", modifier = Modifier.testTag("install-requirements"))
            Text("Минимальная конфигурация рассчитана на небольшую нагрузку. Для параллельных встреч и записи выбирайте рекомендуемую конфигурацию; доступная нагрузка зависит от участников и качества видео.", style = MaterialTheme.typography.bodySmall)
            Text("Нужен root или sudo без пароля. DNS A должен указывать на этот IPv4; IPv6 и CDN пока не поддерживаются.")
            OutlinedTextField(domain, { domain = it }, shape = RoundedCornerShape(10.dp), colors = onboardingFieldColors(), textStyle = onboardingInputStyle(), label = { Text("Домен MeetSpace", fontSize = 11.sp) }, singleLine = true, enabled = !state.busy && state.needsAccess, modifier = Modifier.fillMaxWidth().testTag("install-domain"))
            OutlinedTextField(ip, { ip = it }, shape = RoundedCornerShape(10.dp), colors = onboardingFieldColors(), textStyle = onboardingInputStyle(), label = { Text("Публичный IPv4 VPS", fontSize = 11.sp) }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("install-ip"))
        } else Text("Восстановление задания ${state.ticket.jobId}. Сначала запросим его состояние; продолжение требует отдельного подтверждения.")
        if (state.needsAccess) {
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(useKey, { useKey = it }, enabled = !state.busy); Spacer(Modifier.width(12.dp)); Text("Войти по приватному ключу") }
            if (useKey) OutlinedButton(shape = RoundedCornerShape(10.dp), onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.keyLoaded) "Ключ выбран · заменить" else "Выбрать приватный ключ") }
            OutlinedTextField(password, { password = it }, shape = RoundedCornerShape(10.dp), colors = onboardingFieldColors(), textStyle = onboardingInputStyle(), label = { Text(if (useKey) "Парольная фраза ключа" else "Пароль SSH", fontSize = 11.sp) }, visualTransformation = PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("install-password"))
        } else Text("Доступ SSH уже проверен. Укажите публичный IPv4 этого VPS, чтобы подготовить план установки.", modifier = Modifier.testTag("install-access-verified"))
        Button(shape = RoundedCornerShape(10.dp), onClick = { onAuthenticate(InstallOptions(domain, ip, ""), password, useKey); password = "" }, enabled = !state.busy && (!state.needsAccess || !useKey || state.keyLoaded), modifier = Modifier.heightIn(min = 49.dp).testTag("install-plan")) {
            Text(if (state.ticket == null) "Получить план без установки" else "Проверить состояние задания")
        }
    }
}

@Composable
private fun InstallPlanCard(plan: InstallPlan) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, onboardingTone(0xFFDFE6F4, 0xFF364359)), colors = CardDefaults.cardColors(containerColor = onboardingTone(0xFFF6F8FC, 0xFF252F40))) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("План изменений", style = MaterialTheme.typography.titleLarge)
        val compact = plan.resourceProfile == "compact-test"
        Text(if (compact) "Режим: компактный" else "Режим: стандартный", modifier = Modifier.testTag("install-resource-profile"))
        if (compact) Text("Для небольшой нагрузки: 2 CPU, 4 ГБ RAM и около 20 ГБ свободного диска (18 ГиБ). Параллельные встречи и запись требуют больше ресурсов: рекомендуются 4 CPU, 8 ГБ RAM и 30 ГиБ свободного диска. Доступная нагрузка зависит от участников и качества видео.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("install-resource-guidance"))
        Text("https://${plan.options.domain}\nIPv4: ${plan.options.publicIpv4}\nАрхитектура: ${plan.architecture}\nВерсия: ${plan.version}")
        SelectionContainer { Text("Commit: ${plan.commit}\nSHA-256 плана: ${plan.hash}", style = MaterialTheme.typography.bodySmall) }
        plan.sourceSelection?.let { Text("Источник GitHub: $it", style = MaterialTheme.typography.bodySmall) }
        plan.sourceSha256?.let { SelectionContainer { Text("SHA-256 исходников: $it", style = MaterialTheme.typography.bodySmall) } }
        val dockerAction = when {
            plan.installDockerPluginsOnly -> "Установить недостающие компоненты Docker (${plan.dockerPluginNames}); использовать установленный Docker"
            plan.installDocker -> "Установить закреплённые Docker, Compose и Buildx"
            else -> "Использовать Docker, Compose и Buildx на сервере"
        }
        Text("$dockerAction; скачать проверенный исходный код; собрать 7 образов; создать /opt/meetspace, секреты и закрытые тома; сохранить копию БД; выполнить миграции; настроить HTTPS/WSS и TURN; проверить готовность.")
        Text("Публичные порты: TCP 80, 443, 3478, 5349, 50010; UDP 3478, 50010, 49160–49259. Откройте их в firewall провайдера. Docker может обходить правила UFW. БД, Redis, RabbitMQ и S3 наружу не публикуются.")
        Text("Выбранные версия и commit сохраняются на сервере; повторный запуск продолжает этот же план. Откат данных автоматически не выполняется. Копия БД локальная; внешние резервные копии настраиваются отдельно. Продление сертификата перезапускает TURN. Исходники и базовые образы закреплены, пакеты внутри сборки могут меняться.", style = MaterialTheme.typography.bodySmall)
    } }
}

@Composable
private fun InstallConsent(plan: InstallPlan, disabled: Boolean, onApply: (Boolean, Boolean, Boolean) -> Unit) {
    var confirmed by remember(plan.hash) { mutableStateOf(false) }
    var docker by remember(plan.hash) { mutableStateOf(!plan.installDocker) }
    var terms by remember(plan.hash) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    if (plan.installDocker) Consent(if (plan.installDockerPluginsOnly) "Разрешаю установить недостающие компоненты Docker (${plan.dockerPluginNames}) из официального репозитория" else "Разрешаю установить Docker, Compose и Buildx из официального репозитория", docker, !disabled, "install-docker-consent") { docker = it }
    Consent("Принимаю условия Let's Encrypt", terms, !disabled, "install-acme-consent") { terms = it }
    Text("Для выпуска HTTPS-сертификата домен передаётся Let's Encrypt.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { uri.openUri("https://letsencrypt.org/repository/") }) { Text("Условия Let's Encrypt") }
    Consent("Проверил план и разрешаю изменения на этом VPS", confirmed, !disabled, "install-confirm") { confirmed = it }
    Button(shape = RoundedCornerShape(10.dp), onClick = { onApply(confirmed, docker, terms) }, enabled = !disabled && confirmed && docker && terms,
        modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp).testTag("install-apply")) { Text("Установить MeetSpace") }
}
private val InstallPlan.dockerPluginNames: String get() = missingDockerPlugins.joinToString("/").ifEmpty { "Compose/Buildx" }
@Composable
private fun Consent(label: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, onChange, enabled = enabled, modifier = Modifier.testTag(tag)); Text(label, modifier = Modifier.weight(1f)) }
}
fun phaseLabel(phase: String): String = when (phase.substringBefore(':')) {
    "queued" -> "Запуск задания"; "resuming" -> "Продолжение"; "docker" -> "Docker, Compose и Buildx"; "source" -> "Проверка исходников"
    "images" -> "Сборка образов${phase.substringAfter(':', "").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""}"
    "configuration" -> "Конфигурация и секреты"; "dependencies" -> "База, очередь и хранилище"; "backup" -> "Резервная копия"
    "migrations" -> "Миграции БД"; "https" -> "HTTPS и сертификат"; "services" -> "Запуск MeetSpace"; "readiness" -> "Проверка готовности"; else -> phase
}
