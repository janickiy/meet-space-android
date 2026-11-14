package com.meetspace.app.ui

import android.net.Uri
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.unit.Dp
import com.meetspace.app.*
import com.meetspace.core.model.*
import com.meetspace.core.ssh.installPhases

@Composable
fun InstallerUi(
    state: InstallerUiState, onImportKey: (Uri) -> Unit,
    onAuthenticate: (InstallOptions, String, Boolean) -> Unit,
    onApply: (Boolean, Boolean, Boolean, String, String) -> Unit, onControl: (String) -> Unit,
    onDetach: () -> Unit, onReconnect: () -> Unit, onConnect: (String) -> Unit, onForgetCompleted: () -> Unit = {}, onRefreshPlan: () -> Unit = {},
    onCreateAccount: ((String) -> Unit)? = null,
    onEditOptions: () -> Unit = {},
) {
    BackHandler { if (!state.busy && state.screen == InstallScreen.PLAN && state.ticket == null) onEditOptions() else onDetach() }
    var detailsExpanded by remember(state.ticket?.jobId) { mutableStateOf(false) }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, containerColor = MaterialTheme.colorScheme.background) { padding ->
        IntroFrame(message = listOf(state.screen.name, state.error).joinToString(), modifier = Modifier.padding(padding).imePadding().testTag("installer"), install = true,
            topBar = if (state.screen == InstallScreen.PROGRESS) null else ({
                TextButton(onClick = { if (state.screen == InstallScreen.PLAN && state.ticket == null) onEditOptions() else onDetach() },
                    enabled = !state.busy, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("install-back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Назад")
                }
            })) {
                InstallationHeading(state)
                state.error?.let { InstallNotice(it, error = true, modifier = Modifier.testTag("install-error")) }
                if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Проверка ответа сервера…") }
                if (state.screen == InstallScreen.ACCESS) {
                    InstallAccess(state, onImportKey, onAuthenticate)
                } else if ((state.needsAccess || state.error != null) && state.status?.state != "completed" && state.screen != InstallScreen.PROGRESS) {
                    OutlinedButton(shape = RoundedCornerShape(14.dp), onClick = onReconnect, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("install-reconnect")) { Text("Подключиться снова") }
                }
                if (state.screen == InstallScreen.PLAN) state.plan?.let { plan ->
                    InstallPlanCard(plan)
                    if (state.ticket == null) TextButton(onClick = onEditOptions, enabled = !state.busy,
                        modifier = Modifier.testTag("install-edit-options")) { Text("Изменить параметры") }
                    InstallConsent(plan, state.busy || state.needsAccess || state.error != null, onApply)
                }
                if (state.screen == InstallScreen.PROGRESS) {
                    val status = state.status
                    if (status == null) {
                        InstallNotice("Состояние пока неизвестно. Для проверки подключитесь снова.")
                        if (state.needsAccess || state.error != null) OutlinedButton(shape = RoundedCornerShape(14.dp), onClick = onReconnect, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("install-reconnect")) { Text("Подключиться снова") }
                    } else {
                        InstallationStatusCard(state, status)
                        status.errorCode?.let { InstallNotice(installErrorMessage(it), error = true) }
                        if ((state.needsAccess || state.error != null) && status.state != "completed") OutlinedButton(shape = RoundedCornerShape(14.dp), onClick = onReconnect, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("install-reconnect")) { Text("Подключиться снова") }
                        if (status.state == "completed") {
                            val domain = (state.plan?.options?.domain ?: state.ticket?.plan?.options?.domain)?.takeIf { it.isNotBlank() }
                            val origin = domain?.let { "https://$it" }
                            val admin = state.plan?.options?.admin ?: state.ticket?.plan?.options?.admin
                            if (domain != null && admin != null) InstallAdministratorReady(admin, domain)
                            if (onCreateAccount != null) {
                                Button(shape = RoundedCornerShape(14.dp), onClick = { origin?.let(onCreateAccount) },
                                    enabled = !state.busy && origin != null,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("install-register")) { Text("Создать аккаунт") }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Уже есть аккаунт?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = { origin?.let(onConnect) }, enabled = !state.busy && origin != null,
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("install-login")) { Text("Войти") }
                                }
                            } else {
                                Button(shape = RoundedCornerShape(14.dp), onClick = { origin?.let(onConnect) },
                                    enabled = !state.busy && origin != null,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("install-connect")) { Text("Подключить MeetSpace") }
                            }
                            TextButton(onClick = onForgetCompleted, enabled = !state.busy) { Text("Убрать завершённое задание с устройства") }
                        } else if (!state.needsAccess && state.error == null) {
                            if (status.state in setOf("needs_recovery", "cancelled", "failed")) {
                                InstallNotice("Продолжение использует прежний план и сохранённые на сервере секреты. Миграции защищены журналом; данные и тома не удаляются.")
                                Button(shape = RoundedCornerShape(14.dp), onClick = { onControl("resume") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("install-resume")) { Text("Подтвердить продолжение") }
                            } else {
                                Text("Остановка произойдёт после текущей команды: сборка одного образа может занять до часа. Запущенные сервисы и данные сохранятся.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(shape = RoundedCornerShape(14.dp), onClick = { onControl("cancel") }, enabled = !state.busy && status.state != "cancel_requested", modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("install-cancel")) { Text("Остановить установку на сервере") }
                            }
                        }
                    }
                    OutlinedButton(onClick = { detailsExpanded = !detailsExpanded }, shape = RoundedCornerShape(if (LocalOnboardingWide.current) 20.dp else 18.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).testTag("install-details-toggle").semantics { stateDescription = if (detailsExpanded) "Развернуто" else "Свернуто" }) {
                        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp).rotate(if (detailsExpanded) 180f else 0f), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp)); Text("Подробности установки", Modifier.weight(1f))
                    }
                    if (detailsExpanded) Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.background,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().testTag("install-details")) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SelectionContainer { Text("Задание: ${state.ticket?.jobId}\nCommit: ${state.plan?.commit}", style = MaterialTheme.typography.bodySmall) }
                            Text("Закрытие экрана или обрыв SSH не останавливают серверное задание. Доступ хранится только в памяти открытого мастера.", style = MaterialTheme.typography.bodySmall)
                            if (status?.state == "completed") {
                                Text("Сервисы, версии образов, HTTPS и авторизация TURN проверены с VPS. Доступ из вашей сети будет проверен при подключении. Видеозвонок ещё не проверялся.", style = MaterialTheme.typography.bodySmall)
                                Text("Последний шаг: ${phaseLabel(status.phase)} · завершено ${status.completedPhases.size} из ${installPhases.size}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (status != null) {
                                if (status.state in setOf("needs_recovery", "failed", "cancelled")) InstallationProgressSummary(state, status)
                                if (status.state in setOf("needs_recovery", "failed", "cancelled") || (state.needsAccess || state.error != null) && !status.terminal) {
                                    Text("Этапы установки", style = MaterialTheme.typography.titleMedium)
                                    InstallationPhaseChecklist(status, (state.needsAccess || state.error != null) && !status.terminal)
                                }
                                Text("Журнал этапов", style = MaterialTheme.typography.titleMedium)
                                status.events.takeLast(12).forEach { event -> Text("${event.sequence}. ${phaseLabel(event.phase)} · ${event.state}${event.errorCode?.let { " · $it" } ?: ""}",
                                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    if (state.errorCode == "STATE_UNAVAILABLE" && !state.needsAccess) {
                        OutlinedButton(shape = RoundedCornerShape(14.dp), onClick = onRefreshPlan, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Проверить сервер и обновить план") }
                        state.plan?.let { InstallConsent(it, state.busy, onApply) }
                    }
                }
        }
    }
}

@Composable
private fun InstallationHeading(state: InstallerUiState) {
    val complete = state.status?.state == "completed"
    val recovery = state.status?.state in setOf("failed", "needs_recovery", "cancelled")
    val stale = state.needsAccess || state.error != null
    val title = when {
        complete -> "Пространство готово."
        state.screen == InstallScreen.ACCESS -> if (state.ticket == null) "Подготовим пространство." else "Вернёмся к установке."
        state.screen == InstallScreen.PLAN -> "Всё под вашим контролем."
        recovery -> "Продолжим с этого места."
        stale -> "Проверим состояние."
        else -> "Ваше пространство создаётся."
    }
    val subtitle = when (state.screen) {
        InstallScreen.ACCESS -> if (state.ticket == null) "Выберите способ установки и параметры сервера. Сначала покажем план изменений." else "Подтвердите доступ, чтобы проверить состояние задания."
        InstallScreen.PLAN -> "Проверьте действия и подтвердите установку. До подтверждения сервер не меняется."
        else -> if (complete) "Установка на сервере завершена." else "${state.endpoint?.user.orEmpty()}@${state.endpoint?.host.orEmpty()}:${state.endpoint?.port ?: 22}"
    }
    Column(Modifier.fillMaxWidth()) {
        OnboardingProgress(if (complete) 5 else 4)
        Spacer(Modifier.height(32.dp))
        if (complete) {
            InstallationCompleteArtwork()
            Spacer(Modifier.height(24.dp))
        }
        Text(when {
            complete -> "ГОТОВО К ОБЩЕНИЮ"
            state.screen == InstallScreen.ACCESS -> "ПАРАМЕТРЫ УСТАНОВКИ"
            state.screen == InstallScreen.PLAN -> "04 / ПЛАН ИЗМЕНЕНИЙ"
            else -> "УСТАНОВКА MEETSPACE"
        }, fontSize = if (LocalOnboardingWide.current) 11.sp else 12.sp, lineHeight = 18.sp,
            letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(12.dp))
        Text(subtitle, modifier = Modifier.widthIn(max = 600.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(if (LocalOnboardingWide.current) 8.dp else 4.dp))
    }
}

@Composable
private fun InstallationCompleteArtwork() {
    Box(Modifier.fillMaxWidth().height(115.dp), contentAlignment = Alignment.Center) {
        val orbitColor = onboardingTone(0xFFD6E7DC, 0xFF466D52)
        Canvas(Modifier.size(125.dp, 115.dp)) {
            drawCircle(orbitColor, 51.5.dp.toPx(), Offset(size.width / 2, size.height / 2), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
            drawCircle(Color(0xFFB6D4C1), 4.dp.toPx(), Offset(7.dp.toPx(), 75.dp.toPx()))
        }
        Surface(shape = RoundedCornerShape(23.dp), color = onboardingTone(0xFFECF6EE, 0xFF2F493E),
            border = BorderStroke(1.dp, onboardingTone(0xFFD3E9DA, 0xFF41634F)), modifier = Modifier.size(71.dp).rotate(-8f)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Check, null, tint = onboardingTone(0xFF6CA17B, 0xFFB3D4BC), modifier = Modifier.size(32.dp).rotate(8f)) }
        }
        Text("✦", Modifier.offset(x = 58.dp, y = (-33).dp), color = Color(0xFFB9AFE0), fontSize = 24.sp)
    }
}

@Composable
private fun InstallNotice(text: String, error: Boolean = false, modifier: Modifier = Modifier) {
    val ink = if (error) onboardingTone(0xFFB34250, 0xFFFFB2BE) else onboardingTone(0xFF90651B, 0xFFE8C27B)
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        color = if (error) onboardingTone(0xFFFFF0F2, 0xFF422B35) else onboardingTone(0xFFFFF7E6, 0xFF3A3227),
        border = BorderStroke(1.dp, if (error) onboardingTone(0xFFEDC5CC, 0xFF68404C) else onboardingTone(0xFFEAD6AA, 0xFF5D4D32))) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.padding(top = 2.dp).size(20.dp), tint = ink)
            Text(text, style = MaterialTheme.typography.bodySmall, color = ink, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** Only acknowledged server phases affect progress; stale data never appears as live execution. */
@Composable
private fun InstallationStatusCard(state: InstallerUiState, status: InstallStatus) {
    val stale = (state.needsAccess || state.error != null) && !status.terminal
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
        InstallFormSection(gap = 14.dp) {
            Text("АДРЕС ВАШЕГО ПРОСТРАНСТВА", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text((state.plan ?: state.ticket?.plan)?.options?.domain.orEmpty(), fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("Let's Encrypt · HTTPS проверен с VPS", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(statusText, style = MaterialTheme.typography.bodySmall, color = onboardingTone(0xFF23785E, 0xFF95DEBF),
                modifier = Modifier.testTag("install-status").semantics { liveRegion = LiveRegionMode.Polite })
        }
    } else {
        InstallFormSection(gap = 12.dp) {
            InstallStatusBadge(statusText, Modifier.padding(bottom = 4.dp).testTag("install-status").semantics { liveRegion = LiveRegionMode.Polite })
            Text(if (stale || recovery) "Последний известный этап" else "Текущий этап", fontSize = 13.sp, lineHeight = 20.8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(phaseLabel(status.phase), fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
            InstallationProgressSummary(state, status)
        }
    }
    if (!complete && !recovery && !stale) InstallationPhaseChecklist(status, stale = false)
}

@Composable
private fun InstallStatusBadge(text: String, modifier: Modifier = Modifier) {
    Surface(modifier.then(if (LocalOnboardingWide.current) Modifier.widthIn(max = 150.dp) else Modifier), shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, Modifier.heightIn(min = 28.dp).padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
            lineHeight = if (LocalOnboardingWide.current) 18.2.sp else 19.6.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InstallationProgressSummary(state: InstallerUiState, status: InstallStatus) {
    val completed = status.completedPhases.size.coerceIn(0, installPhases.size)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(progress = { completed / installPhases.size.toFloat() }, modifier = Modifier.weight(1f).height(8.dp), trackColor = MaterialTheme.colorScheme.outlineVariant,
            gapSize = 0.dp, drawStopIndicator = {})
        Text("${completed * 100 / installPhases.size}%", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    Text("Подтверждено этапов: $completed из ${installPhases.size}" + if ((state.needsAccess || state.error != null) && !status.terminal) " · данные требуют проверки" else "",
        modifier = Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InstallationPhaseChecklist(status: InstallStatus, stale: Boolean) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth()) {
        installPhases.forEachIndexed { index, phase ->
            val done = phase in status.completedPhases
            val current = !done && !status.terminal && !stale && phase == status.phase.substringBefore(':')
            val ink = if (done) onboardingTone(0xFF23785E, 0xFF95DEBF) else if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Row(Modifier.fillMaxWidth().drawBehind {
                if (index != installPhases.lastIndex && size.height > 44.dp.toPx()) drawLine(line, Offset(16.dp.toPx(), 38.dp.toPx()), Offset(16.dp.toPx(), size.height - 6.dp.toPx()), 2.dp.toPx())
            }.padding(bottom = if (index == installPhases.lastIndex) 0.dp else 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                Surface(shape = CircleShape, modifier = Modifier.size(32.dp),
                    border = BorderStroke(1.dp, if (done) onboardingTone(0xFFC2E4D5, 0xFF3C5B4F) else if (current) MaterialTheme.colorScheme.primary else line),
                    color = if (done) onboardingTone(0xFFEDF8F3, 0xFF273B35) else if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Box(contentAlignment = Alignment.Center) {
                        if (done) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = ink)
                        else Text((index + 1).toString().padStart(2, '0'), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = ink)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(phaseLabel(phase), style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (current || done) FontWeight.SemiBold else FontWeight.Medium, color = if (done) MaterialTheme.colorScheme.onSurface else ink)
                    Text(when { done -> "Завершено"; current -> if (status.state == "cancel_requested") "Остановка запрошена" else "Выполняется"; stale && phase == status.phase.substringBefore(':') -> "Состояние требует проверки"; else -> "Не подтверждено" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (current && !LocalOnboardingWide.current) InstallStatusBadge("Сейчас")
                }
                if (current && LocalOnboardingWide.current) InstallStatusBadge("Сейчас")
            }
        }
    }
}

@Composable
private fun InstallAccess(state: InstallerUiState, onImport: (Uri) -> Unit, onAuthenticate: (InstallOptions, String, Boolean) -> Unit) {
    var domain by rememberSaveable(state.endpoint) { mutableStateOf(state.options.domain) }
    var ip by rememberSaveable(state.endpoint) { mutableStateOf(state.options.publicIpv4) }
    val acmeEmail = state.options.acmeEmail
    var imageMode by rememberSaveable(state.endpoint) { mutableStateOf(state.options.imageMode) }
    var adminEnabled by rememberSaveable(state.endpoint) { mutableStateOf(state.options.admin != null) }
    var adminUsername by rememberSaveable(state.endpoint) { mutableStateOf(state.options.admin?.username ?: "admin") }
    var adminPort by rememberSaveable(state.endpoint) { mutableStateOf(state.options.admin?.httpsPort?.toString() ?: "8081") }
    val admin = if (adminEnabled) InstallAdminOptions(adminUsername.trim().lowercase(java.util.Locale.ROOT), adminPort.toIntOrNull() ?: 0) else null
    var smtpEnabled by rememberSaveable(state.endpoint) { mutableStateOf(state.options.smtp != null) }
    var smtpHost by rememberSaveable(state.endpoint) { mutableStateOf(state.options.smtp?.host.orEmpty()) }
    var smtpPort by rememberSaveable(state.endpoint) { mutableStateOf(state.options.smtp?.port?.toString() ?: "465") }
    var smtpFrom by rememberSaveable(state.endpoint) { mutableStateOf(state.options.smtp?.from.orEmpty()) }
    var smtpUsername by rememberSaveable(state.endpoint) { mutableStateOf(state.options.smtp?.username.orEmpty()) }
    var smtpStartTls by rememberSaveable(state.endpoint) { mutableStateOf(state.options.smtp?.tlsMode == "starttls") }
    val smtp = if (smtpEnabled) InstallSmtpOptions(smtpHost.trim(), smtpPort.toIntOrNull() ?: 0, smtpFrom.trim(), smtpUsername.trim(), if (smtpStartTls) "starttls" else "tls") else null
    var useKey by rememberSaveable { mutableStateOf(false) }
    // Deliberately not rememberSaveable: no secret in SavedState, disk, UI logs or navigation args.
    var password by remember(state.credentialReset, useKey) { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onImport) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (state.ticket == null) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Способ установки", fontSize = 18.sp, lineHeight = 25.2.sp, fontWeight = FontWeight.SemiBold)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InstallImageMode.entries.forEach { mode ->
                    val selected = imageMode == mode
                    Surface(shape = RoundedCornerShape(16.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().selectable(selected, enabled = !state.busy, role = Role.RadioButton, onClick = { imageMode = mode })
                            .testTag("install-mode-${mode.wire}")) {
                        Row(Modifier.heightIn(min = 104.dp).padding(horizontal = if (LocalOnboardingWide.current) 20.dp else 16.dp, vertical = if (LocalOnboardingWide.current) 20.dp else 18.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (LocalOnboardingWide.current) 16.dp else 12.dp)) {
                            Surface(shape = RoundedCornerShape(12.dp), color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
                                modifier = Modifier.size(if (LocalOnboardingWide.current) 40.dp else 36.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(if (mode == InstallImageMode.IMAGES) Icons.Outlined.Download else Icons.Outlined.Dns, null, Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(if (mode == InstallImageMode.IMAGES) "Готовые образы" else "Сборка на сервере", fontWeight = FontWeight.SemiBold,
                                    fontSize = if (LocalOnboardingWide.current) 14.sp else 16.sp, lineHeight = if (LocalOnboardingWide.current) 21.sp else 24.sp,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text(if (mode == InstallImageMode.IMAGES) "Последний опубликованный пакет для вашего VPS. Без компиляции на сервере."
                                    else "Последний commit основной ветки GitHub: закрепить исходники и собрать образы на VPS. Включает актуальную админку и поддержку LDAP.",
                                    fontSize = if (LocalOnboardingWide.current) 13.sp else 14.sp, lineHeight = if (LocalOnboardingWide.current) 20.8.sp else 22.4.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(selected, onClick = null, enabled = !state.busy)
                        }
                    }
                }
                }
                if (imageMode == InstallImageMode.IMAGES) {
                    Text("Последний опубликованный выпуск выбирается автоматически, включая beta. Версию, коммит и размер покажем перед установкой.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("install-image-latest"))
                }
            }
            InstallFormSection("Требования к VPS") {
                Text("Новый выделенный VPS: Ubuntu 22.04 или 24.04 LTS, amd64 или arm64. Минимум: 2 CPU, 4 ГБ RAM и около 20 ГБ свободного диска (18 ГиБ). Рекомендуется: 4 CPU, 8 ГБ RAM и 30 ГиБ свободно.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("install-requirements"))
                Text("Минимальная конфигурация рассчитана на небольшую нагрузку. Для параллельных встреч и записи выбирайте рекомендуемую конфигурацию; доступная нагрузка зависит от участников и качества видео.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Нужен root или sudo без пароля. DNS A должен указывать на этот IPv4; IPv6 и CDN пока не поддерживаются.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InstallFormSection("Адрес сервера") {
                InstallFieldPair({ InstallOptionField(domain, { domain = it }, "Домен MeetSpace", "install-domain", !state.busy && state.needsAccess, KeyboardType.Uri) },
                    { InstallOptionField(ip, { ip = it }, "Публичный IPv4 VPS", "install-ip", !state.busy) })
            }
            InstallFormSection("HTTPS-сертификат") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Let's Encrypt", fontWeight = FontWeight.SemiBold)
                        Text("Автоматический выпуск и продление сертификата.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Сертификат выпускается для домена MeetSpace. Условия Let's Encrypt подтвердите перед установкой.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InstallFormSection("Административная панель") {
                InstallToggle("Включить админку", adminEnabled, { adminEnabled = it }, enabled = !state.busy, tag = "install-admin-enabled")
                if (adminEnabled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InstallFieldPair({ InstallOptionField(adminUsername, { adminUsername = it }, "Логин администратора", "install-admin-username", !state.busy) },
                        { InstallOptionField(adminPort, { adminPort = it }, "HTTPS-порт админки", "install-admin-port", !state.busy, KeyboardType.Number) })
                    Text("3–40 символов: латинские буквы, цифры, . - _ @ +", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Отдельный вход для управления сервером. Пароль задаётся перед установкой. LDAP / Active Directory и почта настраиваются в админке.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Text("Без админки настройка LDAP в браузере недоступна.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InstallFormSection {
                InstallToggle("Настроить отправку email", smtpEnabled, { smtpEnabled = it }, enabled = !state.busy, tag = "install-smtp-enabled")
                if (smtpEnabled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InstallOptionField(smtpHost, { smtpHost = it }, "Сервер SMTP", "install-smtp-host", !state.busy)
                    InstallToggle(if (smtpStartTls) "STARTTLS · обычно порт 587" else "TLS · обычно порт 465", smtpStartTls,
                        { smtpStartTls = it; smtpPort = if (it) "587" else "465" }, enabled = !state.busy, tag = "install-smtp-starttls")
                    InstallOptionField(smtpPort, { smtpPort = it }, "Порт SMTP", "install-smtp-port", !state.busy, KeyboardType.Number)
                    InstallOptionField(smtpFrom, { smtpFrom = it }, "Email отправителя", "install-smtp-from", !state.busy, KeyboardType.Email)
                    InstallOptionField(smtpUsername, { smtpUsername = it }, "Логин SMTP", "install-smtp-username", !state.busy)
                    Text("Пароль приложения или SMTP будет запрошен перед установкой. На устройстве он не сохраняется.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Без SMTP письма не отправляются. Подключить почту можно позже на сервере.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else Text("Восстановление задания ${state.ticket.jobId}. Сначала запросим его состояние; продолжение требует отдельного подтверждения.")
        if (state.needsAccess) InstallFormSection("Доступ к серверу") {
            InstallToggle("Войти по приватному ключу", useKey, { useKey = it }, enabled = !state.busy, tag = "install-use-key")
            if (useKey) OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (state.keyLoaded) "Ключ выбран · заменить" else "Выбрать приватный ключ") }
            InstallOptionField(password, { password = it }, if (useKey) "Парольная фраза ключа" else "Пароль SSH", "install-password", !state.busy, KeyboardType.Password, secret = true)
        } else Text("Доступ SSH уже проверен. Проверьте параметры установки и учётную запись администратора.", modifier = Modifier.testTag("install-access-verified"))
        Button(shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), onClick = { onAuthenticate(InstallOptions(domain, ip, acmeEmail.trim(), smtp, imageMode, admin = admin), password, useKey); password = "" }, enabled = !state.busy && (!state.needsAccess || !useKey || state.keyLoaded) && (state.ticket != null ||
            (smtp == null || smtp.isValid()) && (admin == null || admin.isValid())), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("install-plan")) {
            Text(if (state.ticket == null) "Получить план без установки" else "Проверить состояние задания", Modifier.weight(1f), textAlign = TextAlign.Center)
            Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun InstallFormSection(title: String? = null, gap: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(if (LocalOnboardingWide.current) 20.dp else 18.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(if (LocalOnboardingWide.current) 24.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
            title?.let { Text(it, fontSize = 18.sp, lineHeight = 25.2.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.25).sp, modifier = Modifier.semantics { heading() }) }
            content()
        }
    }
}

@Composable
private fun InstallFieldPair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    if (LocalOnboardingWide.current) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.weight(1f)) { first() }
        Box(Modifier.weight(1f)) { second() }
    } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) { first(); second() }
}

@Composable
private fun InstallToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean, tag: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Box(Modifier.size(44.dp, 26.dp).background(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape), contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.padding(horizontal = 3.dp).size(20.dp).background(Color.White.copy(alpha = if (enabled) 1f else .5f), CircleShape))
        }
    }
}

@Composable
private fun InstallOptionField(value: String, onChange: (String) -> Unit, label: String, tag: String, enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text, secret: Boolean = false, invalid: Boolean = false) {
    var reveal by remember(tag) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
        OutlinedTextField(value, onChange, shape = RoundedCornerShape(12.dp), colors = onboardingFieldColors(),
            textStyle = onboardingInputStyle().copy(fontSize = 16.sp, lineHeight = 24.sp), singleLine = true, enabled = enabled, isError = invalid,
            visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            trailingIcon = if (secret) ({ IconButton({ reveal = !reveal }, enabled = enabled) {
                Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (reveal) "Скрыть пароль" else "Показать пароль", Modifier.size(20.dp))
            } }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag(tag).semantics { contentDescription = label })
    }
}

@Composable
private fun InstallPlanCard(plan: InstallPlan) {
    val images = plan.options.imageMode == InstallImageMode.IMAGES
    val compact = plan.resourceProfile == "compact-test"
    val dockerAction = when {
        plan.installDockerPluginsOnly -> "Установить недостающие компоненты Docker (${plan.dockerPluginNames}); использовать установленный Docker"
        plan.installDocker -> "Установить закреплённые Docker, Compose и Buildx"
        images -> "Использовать Docker и Compose на сервере"
        else -> "Использовать Docker, Compose и Buildx на сервере"
    }
    InstallFormSection("План изменений") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InstallMetadata("Способ", if (images) "Готовые образы" else "Сборка на сервере", "install-image-mode")
            InstallMetadata("Режим ресурсов", if (compact) "Компактный" else "Стандартный", "install-resource-profile")
            InstallMetadata("Адрес", "https://${plan.options.domain}")
            InstallMetadata("IPv4", plan.options.publicIpv4)
            InstallMetadata("Архитектура", plan.architecture)
            InstallMetadata("Версия", plan.version)
            InstallMetadata("Commit", plan.commit, code = true)
            InstallMetadata("SHA-256 плана", plan.hash, code = true)
            plan.imageArchiveBytes?.let { InstallMetadata("Пакет", "${it / (1024 * 1024)} МиБ", "install-image-size") }
            plan.imageArchiveSha256?.let { InstallMetadata("SHA-256 пакета", it, code = true) }
            plan.sourceSelection?.let { InstallMetadata("Источник GitHub", it) }
            plan.sourceSha256?.let { InstallMetadata("SHA-256 исходников", it, code = true) }
            InstallMetadata("Docker", dockerAction)
            InstallMetadata("HTTPS-сертификат", "Let's Encrypt · автоматический")
            InstallMetadata("Обновление сертификата", "Автоматически")
        }
        if (compact) Text("Для небольшой нагрузки: 2 CPU, 4 ГБ RAM и около 20 ГБ свободного диска (18 ГиБ). Параллельные встречи и запись требуют больше ресурсов: рекомендуются 4 CPU, 8 ГБ RAM и 30 ГиБ свободного диска. Доступная нагрузка зависит от участников и качества видео.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("install-resource-guidance"))
        plan.options.admin?.let { admin ->
            Column(Modifier.testTag("install-admin-plan"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InstallMetadata("Админка", admin.url(plan.options.domain))
                InstallMetadata("Логин", admin.username)
                InstallMetadata("LDAP / Active Directory", "Настройка после установки в админке")
            }
        }
        plan.options.smtp?.let { smtp ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InstallMetadata("Почта", "${smtp.host}:${smtp.port} · ${smtp.tlsMode.uppercase()}")
                InstallMetadata("Отправитель", smtp.from)
                InstallMetadata("Напоминание о встрече", "За 3 часа")
            }
        }
    }
    InstallFormSection("Что будет сделано") {
        val imageAction = if (images) "скачать пакет выпуска и файлы конфигурации; проверить контрольные суммы, архитектуру и отчёты безопасности; загрузить готовые образы в Docker"
            else "скачать исходники выбранного commit основной ветки; собрать образы приложения и админки"
        Text("$dockerAction; $imageAction; создать /opt/meetspace, секреты и закрытые тома; сохранить копию БД; выполнить миграции; настроить HTTPS/WSS и TURN; проверить готовность.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    InstallFormSection("Сеть и важные условия") {
        val adminPort = plan.options.admin?.httpsPort?.let { ", $it (админка)" }.orEmpty()
        Text("Публичные порты: TCP 80, 443, 3478, 5349, 50010$adminPort; UDP 3478, 50010, 49160–49259. Откройте их в firewall провайдера. Docker может обходить правила UFW. БД, Redis, RabbitMQ и S3 наружу не публикуются.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Выбранные режим, версия и commit сохраняются на сервере; повторный запуск продолжает этот же план. Откат данных автоматически не выполняется. Копия БД локальная; внешние резервные копии настраиваются отдельно. Продление сертификата перезапускает TURN." +
            if (images) " Архив закреплён по SHA-256; переход к сборке требует отдельного выбора." else " Исходники и базовые образы закреплены, пакеты внутри сборки могут меняться.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InstallMetadata(label: String, value: String, tag: String? = null, code: Boolean = false) {
    Column(Modifier.fillMaxWidth().then(if (tag == null) Modifier else Modifier.testTag(tag).semantics(mergeDescendants = true) {}), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (value.length > 32 || !LocalOnboardingWide.current && label.length + value.length > 38) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall,
                fontFamily = if (code) FontFamily.Monospace else null, fontWeight = FontWeight.Medium) }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Text(label, Modifier.weight(.9f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun InstallConsent(plan: InstallPlan, disabled: Boolean, onApply: (Boolean, Boolean, Boolean, String, String) -> Unit) {
    var confirmed by remember(plan.hash) { mutableStateOf(false) }
    var docker by remember(plan.hash) { mutableStateOf(!plan.installDocker) }
    var terms by remember(plan.hash) { mutableStateOf(false) }
    // This secret never enters saved state, the public plan, or the persisted ticket.
    var smtpPassword by remember(plan.hash) { mutableStateOf("") }
    var adminPassword by remember(plan.hash) { mutableStateOf("") }
    var adminConfirmation by remember(plan.hash) { mutableStateOf("") }
    val adminPasswordValid = plan.options.admin == null || isValidInstallAdminPassword(adminPassword) && adminPassword == adminConfirmation
    val smtpPasswordValid = plan.options.smtp == null || isValidInstallSmtpPassword(smtpPassword)
    val uri = LocalUriHandler.current
    if (plan.options.admin != null) {
        InstallFormSection("Пароль администратора") {
            InstallOptionField(adminPassword, { adminPassword = it }, "Пароль администратора", "install-admin-password", !disabled, KeyboardType.Password, secret = true)
            InstallOptionField(adminConfirmation, { adminConfirmation = it }, "Повторите пароль", "install-admin-confirmation", !disabled, KeyboardType.Password,
                secret = true, invalid = adminConfirmation.isNotEmpty() && adminConfirmation != adminPassword)
            Text("От 8 до 128 символов. Это отдельная учётная запись админки. Пароль передаётся по SSH при установке и не сохраняется на устройстве.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (plan.options.smtp != null) {
        InstallFormSection("Отправка email") {
            InstallOptionField(smtpPassword, { smtpPassword = it }, "Пароль SMTP / пароль приложения", "install-smtp-password", !disabled, KeyboardType.Password, secret = true)
            Text("Пароль передаётся серверу по SSH только при запуске установки и хранится там в закрытом файле настроек.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (plan.installDocker) Consent(if (plan.installDockerPluginsOnly) "Разрешаю установить недостающие компоненты Docker (${plan.dockerPluginNames}) из официального репозитория" else "Разрешаю установить Docker, Compose и Buildx из официального репозитория", docker, !disabled, "install-docker-consent") { docker = it }
    Consent("Принимаю условия Let's Encrypt", terms, !disabled, "install-acme-consent") { terms = it }
    Text("Для выпуска HTTPS-сертификата домен передаётся Let's Encrypt.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { uri.openUri("https://letsencrypt.org/repository/") }) { Text("Условия Let's Encrypt") }
    Consent("Проверил план и разрешаю изменения на этом VPS", confirmed, !disabled, "install-confirm") { confirmed = it }
    Button(shape = RoundedCornerShape(14.dp), onClick = { onApply(confirmed, docker, terms, smtpPassword, adminPassword); smtpPassword = ""; adminPassword = ""; adminConfirmation = "" }, enabled = !disabled && confirmed && docker && terms && smtpPasswordValid && adminPasswordValid,
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("install-apply")) { Text("Установить MeetSpace") }
}
@Composable
private fun InstallAdministratorReady(admin: InstallAdminOptions, domain: String) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var actionError by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val url = admin.url(domain)
    InstallFormSection("Администрирование") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AdminPanelSettings, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionContainer { Text(url, modifier = Modifier.testTag("install-admin-url")) }
                Text("Логин: ${admin.username}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Откройте админку и войдите с заданным паролем. LDAP / Active Directory настраивается в разделе «Настройки». Пользовательские аккаунты создаются отдельно.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { actionError = runCatching { uri.openUri(url) }.isFailure }, shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("install-admin-open")) {
            Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Открыть админку")
        }
        OutlinedButton(shape = RoundedCornerShape(14.dp), onClick = { scope.launch {
            runCatching { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Админка MeetSpace", url))) }
                .onSuccess { copied = true; actionError = false }.onFailure { actionError = true }
        } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("install-admin-copy")) {
            Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (copied) "Адрес скопирован" else "Скопировать адрес")
        }
        if (actionError) Text("Не удалось открыть или скопировать адрес. Выделите его и скопируйте вручную.", color = MaterialTheme.colorScheme.error)
    }
}

private val InstallPlan.dockerPluginNames: String get() = missingDockerPlugins.joinToString("/").ifEmpty { "Compose/Buildx" }
@Composable
private fun Consent(label: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Checkbox(checked, onChange, enabled = enabled, modifier = Modifier.testTag(tag))
        Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp), fontSize = 14.sp, lineHeight = 21.7.sp)
    }
}
fun phaseLabel(phase: String): String = when (phase.substringBefore(':')) {
    "queued" -> "Запуск задания"; "resuming" -> "Продолжение"; "docker" -> "Docker, Compose и Buildx"; "source" -> "Проверка исходников"
    "images" -> when (phase.substringAfter(':', "")) {
        "download" -> "Загрузка пакета образов"
        "verify" -> "Проверка пакета образов"
        "load" -> "Импорт образов в Docker"
        else -> "Подготовка образов${phase.substringAfter(':', "").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""}"
    }
    "configuration" -> "Конфигурация и секреты"; "dependencies" -> "База, очередь и хранилище"; "backup" -> "Резервная копия"
    "migrations" -> "Миграции БД"; "https" -> "HTTPS и сертификат"; "services" -> "Запуск MeetSpace"; "readiness" -> "Проверка готовности"; else -> phase
}
