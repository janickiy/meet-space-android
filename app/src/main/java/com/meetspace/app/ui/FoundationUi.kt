package com.meetspace.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.window.core.layout.WindowSizeClass
import com.meetspace.app.R
import com.meetspace.core.designsystem.MeetSpaceTokens
import com.meetspace.core.model.Capabilities
import com.meetspace.core.model.RepositoryState
import com.meetspace.core.model.ServerProfile
import com.meetspace.core.model.ThemeMode
import com.meetspace.core.model.RegistrationValidation
import com.meetspace.core.model.RegistrationValidationError
import com.meetspace.core.model.RegistrationField

enum class FoundationScreen { WELCOME, CONNECT, LOGIN, REGISTER, WORKSPACE, INSTALLER }

data class FoundationUiState(
    val screen: FoundationScreen = FoundationScreen.WELCOME,
    val repository: RepositoryState = RepositoryState(),
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val loginEmail: String? = null,
    val loginEmailProfileId: String? = null,
    val accountSection: String? = null,
    val accountCreatedEmail: String? = null,
)

/** All connection and authentication effects belong to the ViewModel/repository. */
@Composable
fun FoundationUi(
    state: FoundationUiState,
    onConnect: (String) -> Unit,
    onSelectProfile: (String) -> Unit,
    onRemoveProfile: (String) -> Unit,
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onNavigate: (FoundationScreen) -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier,
    windowSizeClass: WindowSizeClass = currentWindowAdaptiveInfo().windowSizeClass,
    installerContent: (@Composable () -> Unit)? = null,
    workspaceContent: (@Composable (WorkspaceTab, (WorkspaceTab) -> Unit) -> Unit)? = null,
    onNotifications: (() -> Unit)? = null,
    unreadCount: Int = 0,
    messageThreadOpen: Boolean = false,
    onCallsSearch: (() -> Unit)? = null,
    requestedWorkspaceTab: WorkspaceTab? = null,
    consumeWorkspaceTab: () -> Unit = {},
    onRegister: ((String, String, String, String) -> Unit)? = null,
    onGuest: ((String, String) -> Unit)? = null,
) {
    BackHandler(enabled = state.screen in listOf(FoundationScreen.CONNECT, FoundationScreen.LOGIN, FoundationScreen.REGISTER, FoundationScreen.INSTALLER) && !state.busy && !(state.screen == FoundationScreen.INSTALLER && installerContent != null)) {
        onNavigate(backDestination(state))
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 760.dp
        if (state.screen == FoundationScreen.WORKSPACE) {
            Workspace(state, onLogout, onTheme, onNavigate, onClearMessage, workspaceContent, onNotifications, unreadCount, messageThreadOpen, onCallsSearch, requestedWorkspaceTab, consumeWorkspaceTab)
        } else if (state.screen == FoundationScreen.INSTALLER && installerContent != null) {
            installerContent()
        } else {
            Scaffold(contentWindowInsets = WindowInsets.safeDrawing, containerColor = MaterialTheme.colorScheme.surface) { padding ->
                IntroFrame(message = state.error ?: state.notice, modifier = Modifier.padding(padding).imePadding()) {
                    if (state.screen != FoundationScreen.WELCOME) {
                        TextButton(
                            onClick = { onNavigate(backDestination(state)) },
                            enabled = !state.busy,
                            modifier = Modifier.heightIn(min = MeetSpaceTokens.MinimumTouchTarget).testTag("back"),
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.screen == FoundationScreen.REGISTER) "Ко входу" else "Назад")
                        }
                    }
                    Message(state, onClearMessage)
                    when (state.screen) {
                        FoundationScreen.WELCOME -> Welcome(expanded, state.busy, onNavigate)
                        FoundationScreen.CONNECT -> Connect(state, onConnect, onSelectProfile, onRemoveProfile)
                        FoundationScreen.LOGIN -> Login(state, onLogin, onNavigate, registrationAvailable = onRegister != null, onGuest = onGuest)
                        FoundationScreen.REGISTER -> Register(state, onRegister, onNavigate, onClearMessage)
                        FoundationScreen.INSTALLER -> Installer(onNavigate)
                        FoundationScreen.WORKSPACE -> Unit
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Ваше общение остаётся в вашем пространстве", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
}

internal val LocalOnboardingWide = staticCompositionLocalOf { false }
private val LocalOnboardingWidth = staticCompositionLocalOf { 390.dp }

@Composable
internal fun onboardingTone(light: Long, dark: Long): Color = Color(if (MaterialTheme.colorScheme.background.luminance() < .5f) dark else light)

@Composable
internal fun onboardingInputStyle() = MaterialTheme.typography.bodyMedium.copy(fontSize = if (LocalOnboardingWide.current) 12.sp else 16.sp, lineHeight = if (LocalOnboardingWide.current) 18.sp else 24.sp)

@Composable
internal fun IntroFrame(modifier: Modifier = Modifier, message: String? = null, install: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val formScroll = rememberScrollState()
    LaunchedEffect(message) { if (message != null) formScroll.animateScrollTo(0) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        val generous = maxWidth >= 1100.dp
        val darkIntro = MaterialTheme.colorScheme.background.luminance() < .5f
        val sideTitleSize = if (generous) (maxWidth.value * .038f).coerceIn(34f, 52f) else 37f
        val baseTypography = MaterialTheme.typography
        CompositionLocalProvider(LocalOnboardingWide provides wide, LocalOnboardingWidth provides maxWidth) {
        MaterialTheme(typography = baseTypography.copy(
            headlineLarge = baseTypography.headlineLarge.copy(fontSize = if (!wide) 33.sp else if (maxWidth <= 1000.dp) 34.sp else 37.sp, lineHeight = if (wide) 42.sp else 38.sp, letterSpacing = (-1.6).sp),
            titleLarge = baseTypography.titleLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
            titleMedium = baseTypography.titleMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            titleSmall = baseTypography.titleSmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
            bodyLarge = baseTypography.bodyLarge.copy(fontSize = if (wide) 13.sp else 12.sp, lineHeight = 22.sp),
            bodyMedium = baseTypography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 20.sp),
            bodySmall = baseTypography.bodySmall.copy(fontSize = 10.sp, lineHeight = 16.sp),
            labelLarge = baseTypography.labelLarge.copy(fontSize = 12.sp, lineHeight = 18.sp),
            labelMedium = baseTypography.labelMedium.copy(fontSize = 11.sp, lineHeight = 16.sp),
            labelSmall = baseTypography.labelSmall.copy(fontSize = if (wide) 10.sp else 8.sp, lineHeight = 15.sp),
        )) {
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                Column(Modifier.weight(if (generous) 1f else .83f).fillMaxHeight().background(if (darkIntro) Color(0xFF222B3B) else Color(0xFFEEF1F9))
                    .verticalScroll(rememberScrollState()).padding(horizontal = if (generous) 55.dp else 29.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Brand()
                    Spacer(Modifier.height(if (generous) 40.dp else 20.dp))
                    Eyebrow(if (install) "ВАШ СЕРВЕР. ВАШИ ПРАВИЛА." else "БЛИЖЕ К КОМАНДЕ.")
                    Text(if (install) "Своё пространство.\nПолный контроль." else "Быть на одной\nволне.",
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = sideTitleSize.sp, lineHeight = (sideTitleSize * 1.09f).sp, letterSpacing = (-2.3).sp), modifier = Modifier.semantics { heading() })
                    Text(if (install) "Подключите удалённый сервер, а мы покажем каждый шаг настройки." else "Встречи, сообщения и идеи —\nв одном пространстве.",
                        fontSize = if (generous) 15.sp else 12.sp, lineHeight = if (generous) 25.sp else 20.sp, color = if (darkIntro) Color(0xFF99A7BD) else Color(0xFF687285))
                    ConnectionArtwork(Modifier.fillMaxWidth().height(if (generous) 270.dp else 230.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Security, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("На вашей инфраструктуре", fontSize = 10.sp, color = if (darkIntro) Color(0xFF99A7BD) else Color(0xFF7D8799))
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                val availableHeight = maxHeight
                Column(Modifier.fillMaxWidth().verticalScroll(formScroll).heightIn(min = availableHeight)
                    .padding(horizontal = if (generous) 62.dp else if (wide) 30.dp else 25.dp, vertical = if (wide) 34.dp else 21.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = if (wide) Arrangement.Center else Arrangement.Top) {
                    Column(Modifier.widthIn(max = if (wide) 418.dp else 470.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (!wide) Brand(phone = true)
                        content()
                    }
                }
            }
        }
        }
        }
    }
}

@Composable
internal fun Brand(modifier: Modifier = Modifier, compact: Boolean = false, phone: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_meetspace), contentDescription = null, modifier = Modifier.size(if (compact) 32.dp else if (phone) 27.dp else 38.dp))
        if (!compact) {
            Row {
                Text("MeetSpace", style = MaterialTheme.typography.headlineSmall.copy(fontSize = if (phone) 20.sp else 24.sp), fontWeight = FontWeight.SemiBold)
                Text(".", style = MaterialTheme.typography.headlineSmall.copy(fontSize = if (phone) 20.sp else 24.sp), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Welcome(expanded: Boolean, busy: Boolean, onNavigate: (FoundationScreen) -> Unit) {
    val narrowTablet = expanded && LocalOnboardingWidth.current <= 1000.dp
    val smallPhone = LocalOnboardingWidth.current < 360.dp
    val titleStyle = MaterialTheme.typography.displaySmall.copy(
        fontSize = if (narrowTablet) 38.sp else if (expanded) 43.sp else if (smallPhone) 34.sp else 37.sp,
        lineHeight = if (narrowTablet) 43.sp else if (expanded) 49.sp else if (smallPhone) 40.sp else 43.sp,
        letterSpacing = if (expanded) (-1.9).sp else (-1.7).sp,
    )
    Column(Modifier.testTag("welcome"), verticalArrangement = Arrangement.spacedBy(if (expanded) 20.dp else 14.dp)) {
        Eyebrow("ЕДИНОЕ ПРОСТРАНСТВО")
        Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
            Text("Команда ближе.", style = titleStyle)
            Text("Идей больше.", style = titleStyle, color = MaterialTheme.colorScheme.primary)
        }
        Text("MeetSpace — единое пространство для встреч и общения.",
            fontSize = if (narrowTablet) 13.sp else if (expanded) 15.sp else if (smallPhone) 11.sp else 12.sp, lineHeight = if (expanded) 25.sp else 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!expanded) ConnectionArtwork(Modifier.fillMaxWidth().height(if (smallPhone) 179.dp else 207.dp))
        ChoiceCard("Подключиться к серверу", "У меня уже есть адрес MeetSpace", Icons.Outlined.Link, primary = true, compact = !expanded, enabled = !busy, modifier = Modifier.testTag("welcome-connect")) {
            onNavigate(FoundationScreen.CONNECT)
        }
        ChoiceCard("Развернуть свой сервер", "Создать пространство на своём VPS", Icons.Outlined.Dns, compact = !expanded, enabled = !busy, modifier = Modifier.testTag("welcome-installer")) {
            onNavigate(FoundationScreen.INSTALLER)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("Встречи" to Icons.Outlined.Videocam, "Сообщения" to Icons.Outlined.ChatBubbleOutline, "Ваш сервер" to Icons.Outlined.Security).forEach { (title, icon) ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Decorative onboarding scene; its labels are not application state. */
@Composable
private fun ConnectionArtwork(modifier: Modifier = Modifier) = OnboardingArtwork(modifier)

@Composable
internal fun onboardingFieldColors(): androidx.compose.material3.TextFieldColors {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val container = if (dark) Color(0xFF242F3E) else Color.White
    val ink = if (dark) Color(0xFFE7EDF8) else Color(0xFF202737)
    val border = if (dark) Color(0xFF3C4960) else Color(0xFFDFE4ED)
    val label = if (dark) Color(0xFFB4BFD2) else Color(0xFF596479)
    val placeholder = if (dark) Color(0xFF7F8CA2) else Color(0xFFA3AAB7)
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = container,
        unfocusedContainerColor = container,
        disabledContainerColor = container,
        errorContainerColor = container,
        focusedTextColor = ink,
        unfocusedTextColor = ink,
        disabledTextColor = ink.copy(alpha = .6f),
        unfocusedBorderColor = border,
        disabledBorderColor = border,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = label,
        disabledLabelColor = label.copy(alpha = .6f),
        focusedPlaceholderColor = placeholder,
        unfocusedPlaceholderColor = placeholder,
        disabledPlaceholderColor = placeholder.copy(alpha = .6f),
        focusedTrailingIconColor = label,
        unfocusedTrailingIconColor = label,
    )
}

@Composable
private fun Connect(state: FoundationUiState, onConnect: (String) -> Unit, onSelectProfile: (String) -> Unit, onRemoveProfile: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.testTag("connect"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        IntroHeading("01 / ПОДКЛЮЧЕНИЕ", "Где ваша\nкоманда?", "Введите адрес сервера MeetSpace. Его можно узнать у администратора команды.")
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Адрес сервера") },
            placeholder = { Text("https://meet.example.org") },
            singleLine = true,
            enabled = !state.busy,
            colors = onboardingFieldColors(),
            textStyle = onboardingInputStyle(), shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().testTag("server-url"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank() && !state.busy) { keyboard?.hide(); onConnect(url) } }),
        )
        Hint(Icons.Outlined.Lock, "Защищённое подключение по HTTPS")
        PrimaryButton("Проверить адрес", state.busy, url.isNotBlank(), "connect-submit") { keyboard?.hide(); onConnect(url) }
        InfoCard(Icons.Outlined.Info, "Каждый сервер — отдельное пространство. Аккаунт вашей команды работает на её сервере.")
        if (state.repository.profiles.isNotEmpty()) {
            Text("Сохранённые серверы", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            ServerProfiles(state.repository.profiles, state.repository.selectedProfileId, state.busy, onSelectProfile, onRemoveProfile)
        }
    }
}

@Composable
private fun ServerProfiles(profiles: List<ServerProfile>, selectedId: String?, busy: Boolean, onSelectProfile: (String) -> Unit, onRemoveProfile: (String) -> Unit) {
    var removalId by remember { mutableStateOf<String?>(null) }
    val removal = profiles.firstOrNull { it.id == removalId }
    profiles.forEach { profile ->
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clickable(enabled = !busy, role = Role.Button, onClick = { onSelectProfile(profile.id) }).padding(16.dp).heightIn(min = MeetSpaceTokens.MinimumTouchTarget).testTag("server-profile-${profile.id}"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(profile.origin, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        profile.accountEmail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        if (profile.id == selectedId) Text("Выбран", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = { removalId = profile.id }, enabled = !busy, modifier = Modifier.size(MeetSpaceTokens.MinimumTouchTarget).testTag("remove-profile-${profile.id}")) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить профиль ${profile.origin}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (removal != null) {
        AlertDialog(
            onDismissRequest = { removalId = null },
            title = { Text("Удалить сервер?") },
            text = { Text("Профиль ${removal.origin} и его сохранённый сеанс будут удалены с этого устройства.") },
            confirmButton = { TextButton(onClick = { removalId = null; onRemoveProfile(removal.id) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removalId = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Login(state: FoundationUiState, onLogin: (String, String) -> Unit, onNavigate: (FoundationScreen) -> Unit, registrationAvailable: Boolean, onGuest: ((String, String) -> Unit)?) {
    var guest by remember { mutableStateOf(false) }
    val profile = state.repository.selectedProfile
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    var email by rememberSaveable(profile?.id) { mutableStateOf(
        state.loginEmail?.takeIf { state.loginEmailProfileId == profile?.id } ?: profile?.accountEmail.orEmpty()) }
    // Passwords must never enter rememberSaveable, SavedStateHandle, or navigation state.
    var password by remember(profile?.id) { mutableStateOf("") }
    var passwordVisible by remember(profile?.id) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (email.isNotBlank() && password.isNotEmpty() && !state.busy) {
            keyboard?.hide()
            val submittedPassword = password
            password = ""
            passwordVisible = false
            onLogin(email.trim(), submittedPassword)
        }
    }
    Column(Modifier.testTag("login"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        IntroHeading("02 / ВХОД В ПРОСТРАНСТВО", "С возвращением.", "Ваша команда уже здесь.")
        Surface(shape = RoundedCornerShape(14.dp),
            color = if (dark) Color(0xFF252F40) else Color(0xFFFAFBFD),
            contentColor = if (dark) Color(0xFFA5B3C9) else Color(0xFF788398),
            border = BorderStroke(1.dp, if (dark) Color(0xFF364359) else Color(0xFFE5E8F0))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Dns, null, Modifier.size(20.dp), tint = Color(0xFF8C97AA))
                Text(profile?.origin.orEmpty().removePrefix("https://"), fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onNavigate(FoundationScreen.CONNECT) }, enabled = !state.busy) { Text("Изменить", fontSize = 12.sp) }
            }
        }
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("Электронная почта") }, singleLine = true, enabled = !state.busy,
            colors = onboardingFieldColors(),
            textStyle = onboardingInputStyle(), shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("email"),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Пароль") }, singleLine = true, enabled = !state.busy,
            colors = onboardingFieldColors(),
            textStyle = onboardingInputStyle(), shape = RoundedCornerShape(10.dp),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }, modifier = Modifier.size(MeetSpaceTokens.MinimumTouchTarget)) {
                    Icon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().testTag("password"),
        )
        if (onGuest != null) TextButton({ guest = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("open-guest")) { Text("Войти гостем по приглашению") }
        PrimaryButton("Войти в MeetSpace", state.busy, email.isNotBlank() && password.isNotEmpty(), "login-submit", submit)
        Text("Если не получается войти, обратитесь к администратору этого сервера.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (registrationAvailable) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("Нет аккаунта?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onNavigate(FoundationScreen.REGISTER) }, enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("open-register")) { Text("Создать аккаунт") }
            }
        }
    }
    if (guest && onGuest != null) GuestJoinDialog(state, onGuest) { guest = false }
}

@Composable
private fun Register(state: FoundationUiState, onRegister: ((String, String, String, String) -> Unit)?,
    onNavigate: (FoundationScreen) -> Unit, onClearMessage: () -> Unit) {
    val profile = state.repository.selectedProfile
    var name by rememberSaveable(profile?.id) { mutableStateOf("") }
    var email by rememberSaveable(profile?.id) { mutableStateOf("") }
    // Only non-secret fields survive rotation. Both passwords and visibility reset.
    var password by remember(profile?.id) { mutableStateOf("") }
    var confirmation by remember(profile?.id) { mutableStateOf("") }
    var passwordVisible by remember(profile?.id) { mutableStateOf(false) }
    var confirmationVisible by remember(profile?.id) { mutableStateOf(false) }
    var error by remember(profile?.id) { mutableStateOf<RegistrationValidationError?>(null) }
    val focus = remember { RegistrationField.entries.associateWith { FocusRequester() } }
    val keyboard = LocalSoftwareKeyboardController.current
    val edit = { error = null; if (state.error != null) onClearMessage() }
    val submit = {
        if (!state.busy && onRegister != null) {
            val invalid = RegistrationValidation.error(name, email, password, confirmation)
            error = invalid
            if (invalid != null) focus.getValue(invalid.field).requestFocus()
            else {
                keyboard?.hide()
                val submittedPassword = password
                val submittedConfirmation = confirmation
                password = ""; confirmation = ""
                passwordVisible = false; confirmationVisible = false
                onRegister(name.trim(), email.trim(), submittedPassword, submittedConfirmation)
            }
        }
    }
    Column(Modifier.testTag("register"), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        IntroHeading("АККАУНТ MEETSPACE", "Создайте аккаунт.", "Для встреч и общения с вашей командой.")
        Surface(shape = RoundedCornerShape(14.dp), color = onboardingTone(0xFFFAFBFD, 0xFF252F40),
            border = BorderStroke(1.dp, onboardingTone(0xFFE5E8F0, 0xFF364359))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Dns, null, Modifier.size(20.dp), tint = onboardingTone(0xFF8C97AA, 0xFFA5B3C9))
                Text(profile?.origin.orEmpty().removePrefix("https://"), fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onNavigate(FoundationScreen.CONNECT) }, enabled = !state.busy) { Text("Изменить", fontSize = 12.sp) }
            }
        }
        RegistrationTextField("Имя для встреч", "Как вас зовут", "register-name", name, { name = it; edit() },
            state.busy, error?.field == RegistrationField.NAME, focus.getValue(RegistrationField.NAME),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        RegistrationTextField("Электронная почта", "you@company.ru", "register-email", email, { email = it; edit() },
            state.busy, error?.field == RegistrationField.EMAIL, focus.getValue(RegistrationField.EMAIL),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
        RegistrationPasswordField("Пароль", "register-password", password, { password = it; edit() }, passwordVisible,
            { passwordVisible = !passwordVisible }, state.busy, error?.field == RegistrationField.PASSWORD,
            focus.getValue(RegistrationField.PASSWORD), confirmation = false, onSubmit = submit)
        RegistrationPasswordField("Повторите пароль", "register-confirm", confirmation, { confirmation = it; edit() }, confirmationVisible,
            { confirmationVisible = !confirmationVisible }, state.busy, error?.field == RegistrationField.CONFIRMATION,
            focus.getValue(RegistrationField.CONFIRMATION), confirmation = true, onSubmit = submit)
        error?.let { Text(it.message, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("register-error")) }
        PrimaryButton("Создать аккаунт", state.busy, onRegister != null, "register-submit", submit)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("Уже есть аккаунт?", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onNavigate(FoundationScreen.LOGIN) }, enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("register-login")) { Text("Войти") }
        }
    }
}

@Composable
private fun RegistrationPasswordField(label: String, tag: String, value: String, onChange: (String) -> Unit,
    visible: Boolean, onToggle: () -> Unit, busy: Boolean, isError: Boolean, focus: FocusRequester,
    confirmation: Boolean, onSubmit: () -> Unit) {
    RegistrationTextField(label, if (confirmation) "Ещё раз введите пароль" else "Минимум 8 символов", tag,
        value, onChange, busy, isError, focus,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { IconButton(onClick = onToggle, enabled = !busy, modifier = Modifier.size(48.dp)) {
            Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = "${if (visible) "Скрыть" else "Показать"} ${if (confirmation) "повтор пароля" else "пароль"}",
                tint = onboardingTone(0xFF596479, 0xFFB4BFD2))
        } },
        hint = if (confirmation) null else "От 8 до 128 символов.",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (confirmation) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }))
}

/** Compact account form: a permanent label, a 6 dp gap, and an input with a 48 dp touch target. */
@Composable
private fun RegistrationTextField(label: String, placeholder: String, tag: String, value: String,
    onChange: (String) -> Unit, busy: Boolean, isError: Boolean, focus: FocusRequester,
    keyboardOptions: KeyboardOptions, keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    hint: String? = null, trailingIcon: (@Composable () -> Unit)? = null) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val inputStyle = onboardingInputStyle()
    val ink = onboardingTone(0xFF202737, 0xFFE7EDF8)
    val border = when {
        isError -> MaterialTheme.colorScheme.error
        focused -> MaterialTheme.colorScheme.primary
        else -> onboardingTone(0xFFDFE4ED, 0xFF3C4960)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
            color = onboardingTone(0xFF596479, 0xFFB4BFD2))
        BasicTextField(value = value, onValueChange = onChange, singleLine = true, enabled = !busy,
            textStyle = inputStyle.copy(color = if (busy) ink.copy(alpha = .6f) else ink),
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            visualTransformation = visualTransformation, interactionSource = interactionSource,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(focus)
                .testTag(tag).semantics {
                    contentDescription = label
                    if (isError) error("Проверьте поле «$label».")
                }
                .clip(RoundedCornerShape(10.dp))
                .background(onboardingTone(0xFFFFFFFF, 0xFF242F3E))
                .border(1.dp, border, RoundedCornerShape(10.dp)),
            decorationBox = { innerTextField ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 13.dp, end = if (trailingIcon == null) 13.dp else 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        if (value.isEmpty()) Text(placeholder, style = inputStyle,
                            color = onboardingTone(0xFFA3AAB7, 0xFF7F8CA2))
                        innerTextField()
                    }
                    trailingIcon?.invoke()
                }
            })
        hint?.let { Text(it, fontSize = 10.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Installer(onNavigate: (FoundationScreen) -> Unit) {
    Column(Modifier.testTag("installer"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        IntroHeading("ВАШ СЕРВЕР. ВАШИ ПРАВИЛА.", "Своё пространство. Полный контроль.", "MeetSpace можно разместить на собственном Linux VPS.")
        InfoCard(Icons.Outlined.Info, "Установка через Android появится в отдельном этапе. Сейчас приложение подключается к уже развёрнутому серверу.")
        Text("Что нужно подготовить", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        ChecklistItem("Отдельный Linux VPS с публичным IP-адресом")
        ChecklistItem("Домен, DNS и действующий сертификат HTTPS")
        ChecklistItem("Доступ по SSH и проверенный отпечаток ключа сервера")
        ChecklistItem("Резервная копия и способ восстановить доступ через панель провайдера")
        Text("Перед установкой приложение должно показать план изменений и подтвердить сервер. Настройка SSH и запуск команд пока недоступны.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { onNavigate(FoundationScreen.CONNECT) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Сервер уже готов — подключиться", modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null)
        }
    }
}

enum class WorkspaceTab(val title: String, val tag: String, val icon: ImageVector) {
    HOME("Главная", "home", Icons.Outlined.Home),
    MESSAGES("Чаты", "messages", Icons.Outlined.Forum),
    CALLS("Встречи", "calls", Icons.Outlined.Videocam),
    CALENDAR("Календарь", "calendar", Icons.Outlined.CalendarMonth),
    PROFILE("Профиль", "profile", Icons.Outlined.PersonOutline),
}

/** The three primary destinations stay in the same place on phones, tablets and split screens. */
private val primaryWorkspaceTabs = listOf(WorkspaceTab.CALLS, WorkspaceTab.MESSAGES, WorkspaceTab.PROFILE)

@Composable
private fun Workspace(
    state: FoundationUiState,
    onLogout: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onNavigate: (FoundationScreen) -> Unit,
    onClearMessage: () -> Unit,
    workspaceContent: (@Composable (WorkspaceTab, (WorkspaceTab) -> Unit) -> Unit)?,
    onNotifications: (() -> Unit)?,
    unreadCount: Int,
    messageThreadOpen: Boolean,
    onCallsSearch: (() -> Unit)?,
    requestedWorkspaceTab: WorkspaceTab?,
    consumeWorkspaceTab: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(WorkspaceTab.CALLS) }
    LaunchedEffect(requestedWorkspaceTab) { requestedWorkspaceTab?.let { selectedTab = it; consumeWorkspaceTab() } }
    val contentScroll = rememberScrollState()
    LaunchedEffect(selectedTab, state.error, state.notice) { contentScroll.animateScrollTo(0) }
    BackHandler(enabled = selectedTab == WorkspaceTab.CALENDAR) { selectedTab = WorkspaceTab.CALLS }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("workspace")) {
        val tablet = maxWidth >= 780.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!(selectedTab == WorkspaceTab.MESSAGES && messageThreadOpen && !tablet)) WorkspaceNavigation(
                    selectedTab = selectedTab,
                    onSelect = { selectedTab = it },
                    profileLabel = state.repository.session?.user?.displayLabel.orEmpty(),
                    tablet = tablet,
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                // Messaging owns its composer and compact toolbar, so keep their height bounded.
                if (selectedTab == WorkspaceTab.CALENDAR || selectedTab == WorkspaceTab.PROFILE || selectedTab == WorkspaceTab.HOME || (selectedTab == WorkspaceTab.CALLS && workspaceContent == null)) {
                    WorkspaceHeader(selectedTab, tablet, onNotifications, unreadCount, onCallsSearch) { selectedTab = it }
                }
                if (state.error != null || state.notice != null) {
                    Box(Modifier.padding(horizontal = if (tablet) 24.dp else 20.dp, vertical = 8.dp)) {
                        Message(state, onClearMessage)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (workspaceContent != null) {
                        workspaceContent(selectedTab) { selectedTab = it }
                    } else when (selectedTab) {
                        WorkspaceTab.HOME -> HomeDashboard(state.repository, MessagingUiState(), MessagingActions(), state.busy, state.busy,
                            onCreate = { selectedTab = WorkspaceTab.CALLS }, onNewChat = { selectedTab = WorkspaceTab.MESSAGES },
                            onJoin = { selectedTab = WorkspaceTab.CALLS }, onSchedule = { selectedTab = WorkspaceTab.CALENDAR },
                            onOpenChat = { selectedTab = WorkspaceTab.MESSAGES }, onFolders = { selectedTab = WorkspaceTab.MESSAGES },
                            onProfile = { selectedTab = WorkspaceTab.PROFILE })
                        WorkspaceTab.PROFILE -> ProfileWorkspace(state.repository, MeetingsUiState(), MeetingsActions(),
                            AppSettingsUiState("", 0, false, false, false, false, false), AppSettingsActions(), state.busy,
                            onTheme, onLogout, { onNavigate(FoundationScreen.CONNECT) }, { selectedTab = WorkspaceTab.MESSAGES })
                        else -> Column(Modifier.fillMaxSize().verticalScroll(contentScroll).padding(20.dp)) {
                            FutureFeature(selectedTab.title, selectedTab.title, "Подключите рабочее пространство для загрузки данных.", selectedTab.icon, selectedTab.tag)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeader(tab: WorkspaceTab, tablet: Boolean, onNotifications: (() -> Unit)?, unreadCount: Int, onCallsSearch: (() -> Unit)?, onTab: (WorkspaceTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (tablet) 84.dp else 77.dp)
            .padding(start = if (tablet) 30.dp else 20.dp, end = if (tablet) 24.dp else 14.dp, bottom = if (tablet) 15.dp else 10.dp)
            .testTag("workspace-header"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tablet) Brand(compact = true)
        Text(if (tab == WorkspaceTab.PROFILE) "Настройки аккаунта" else tab.title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = if (tablet) 25.sp else 23.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
        if (tab == WorkspaceTab.CALENDAR) {
            IconButton(onClick = { onTab(WorkspaceTab.CALLS) }, modifier = Modifier.testTag("calendar-back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад к встречам")
            }
        }
        if (tab == WorkspaceTab.CALLS) {
            IconButton(onClick = onCallsSearch ?: { onTab(WorkspaceTab.CALENDAR) }, modifier = Modifier.testTag(if (onCallsSearch != null) "calls-search-toggle" else "calls-calendar")) {
                Icon(if (onCallsSearch != null) Icons.Outlined.Search else Icons.Outlined.CalendarMonth, contentDescription = if (onCallsSearch != null) "Найти звонок" else "Открыть календарь", modifier = Modifier.size(27.dp))
            }
        }
        if (tab == WorkspaceTab.HOME && onNotifications != null) {
            IconButton(onClick = onNotifications, modifier = Modifier.testTag("workspace-notifications")) {
                Box {
                    Icon(Icons.Outlined.NotificationsNone,
                        contentDescription = if (unreadCount > 0) "Уведомления, непрочитанных: $unreadCount" else "Уведомления",
                        modifier = Modifier.size(27.dp))
                    if (unreadCount > 0) Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceNavigation(selectedTab: WorkspaceTab, onSelect: (WorkspaceTab) -> Unit, profileLabel: String, tablet: Boolean) {
    val activeTab = if (selectedTab == WorkspaceTab.CALENDAR) WorkspaceTab.CALLS else selectedTab
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, modifier = Modifier.testTag("bottom-navigation")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.align(Alignment.CenterHorizontally).widthIn(max = if (tablet) 600.dp else 510.dp).fillMaxWidth()
                    .padding(start = if (tablet) 0.dp else 12.dp, end = if (tablet) 0.dp else 12.dp, top = if (tablet) 10.dp else 7.dp)
                    .selectableGroup(),
            ) {
                primaryWorkspaceTabs.forEach { tab ->
                    val selected = tab == activeTab
                    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).heightIn(min = 54.dp)
                            .selectable(selected = selected, onClick = { onSelect(tab) }, role = Role.Tab)
                            .padding(horizontal = 4.dp, vertical = 2.dp).testTag("tab-${tab.tag}")
                            .semantics { contentDescription = tab.title },
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (tab == WorkspaceTab.PROFILE) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
                                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier.size(29.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(initials(profileLabel), fontSize = 9.sp, lineHeight = 11.sp,
                                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        } else Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(35.dp).background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(9.dp)).padding(4.dp))
                        Text(tab.title, fontSize = 11.sp, lineHeight = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun FutureFeature(title: String, subtitle: String, explanation: String, icon: ImageVector, tag: String) {
    Surface(shape = MeetSpaceTokens.CardShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(20.dp).size(36.dp)) }
            Text(subtitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(explanation, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Раздел «$title» пока недоступен", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ServerInfo(repository: RepositoryState) {
    Surface(shape = MeetSpaceTokens.CardShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Пространство команды", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            Text(repository.selectedProfile?.origin.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            repository.version?.let { version ->
                Detail("Версия сервера", version.version.ifBlank { "Не указана" })
                Detail("Коммит", version.commit.ifBlank { "Не указан" })
                Detail("Дата сборки", version.buildTime.ifBlank { "Не указана" })
            } ?: Text("Сведения о сборке пока не получены", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun CapabilityInfo(capabilities: Capabilities?) {
    Surface(shape = MeetSpaceTokens.CardShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Возможности сервера", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("Возможности сообщает выбранный сервер. Доступ к материалам зависит от его настроек и ваших прав.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (capabilities == null) Text("Данные пока не получены", style = MaterialTheme.typography.bodyMedium)
            else {
                listOf(
                    "Субтитры в реальном времени" to capabilities.liveCaptions,
                    "Расшифровка встречи" to capabilities.transcription,
                    "Итоги с помощью ИИ" to capabilities.aiSummary,
                    "Смысловой поиск" to capabilities.semanticSearch,
                    "Аналитика встреч" to capabilities.meetingAnalytics,
                ).forEach { (name, enabled) -> Detail(name, if (enabled) "Доступно на сервере" else "Не заявлено") }
                Detail("Режимы записи", capabilities.recordingModes.joinToString().ifBlank { "Не заявлены" })
                if (capabilities.buildVersion.isNotBlank()) Detail("Сборка API", capabilities.buildVersion)
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun IntroHeading(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Eyebrow(eyebrow)
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Eyebrow(text: String) { Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
private fun ChoiceCard(title: String, subtitle: String, icon: ImageVector, modifier: Modifier = Modifier, primary: Boolean = false, compact: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Card(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = if (compact) 79.dp else 90.dp),
        shape = RoundedCornerShape(if (compact) 12.dp else 15.dp),
        colors = CardDefaults.cardColors(containerColor = if (primary) { if (dark) Color(0xFF4676ED) else MaterialTheme.colorScheme.primary } else if (dark) Color(0xFF252E3B) else Color.White,
            contentColor = if (primary) Color.White else MaterialTheme.colorScheme.onSurface),
        border = if (primary) null else BorderStroke(1.dp, if (dark) Color(0xFF374358) else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = if (compact) 14.dp else 17.dp, vertical = if (compact) 15.dp else 20.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(11.dp), color = if (primary) Color.White.copy(alpha = if (dark) .114f else .08f) else if (dark) Color(0xFF354053) else Color(0xFFF2F4F8)) {
                Box(Modifier.size(if (compact) 36.dp else 40.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = if (primary) Color.White else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(title, fontSize = if (compact) 12.sp else 14.sp, lineHeight = if (compact) 16.sp else 18.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = if (compact) 9.sp else 10.5.sp, lineHeight = if (compact) 13.sp else 16.sp,
                    color = if (primary) { if (dark) Color(0xFFDCE6FF) else Color(0xFFDCE5FF) } else if (dark) Color(0xFFA2AEC3) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ArrowOutward, null, Modifier.size(if (compact) 17.dp else 20.dp), tint = if (primary) { if (dark) Color(0xFFDCE6FF) else Color(0xFFDCE5FF) } else Color(0xFF8F99AB))
        }
    }
}

@Composable
private fun PrimaryButton(label: String, busy: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp).testTag(tag), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 15.dp, vertical = 12.dp)) {
        if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }
        Text(if (busy) "Пожалуйста, подождите…" else label, modifier = Modifier.weight(1f))
        if (!busy) Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Hint(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoCard(icon: ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(11.dp), color = onboardingTone(0xFFF6F8FC, 0xFF252F40), border = BorderStroke(1.dp, onboardingTone(0xFFEDF0F5, 0xFF364359))) {
        Row(Modifier.fillMaxWidth().padding(15.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = onboardingTone(0xFF8595B1, 0xFFA5B3C9), modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = onboardingTone(0xFF7C879B, 0xFFA5B3C9))
        }
    }
}

@Composable
private fun ChecklistItem(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Message(state: FoundationUiState, onClearMessage: () -> Unit) {
    val message = state.error ?: state.notice ?: return
    val error = state.error != null
    Surface(shape = RoundedCornerShape(18.dp), color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag(if (error) "error" else "notice")) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
            IconButton(onClick = onClearMessage, modifier = Modifier.size(MeetSpaceTokens.MinimumTouchTarget)) { Icon(Icons.Outlined.Close, contentDescription = "Закрыть сообщение") }
        }
    }
}

private fun initials(label: String): String = label.trim().split(Regex("\\s+")).filter(String::isNotBlank).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "MS" }

private fun backDestination(state: FoundationUiState): FoundationScreen = when {
    state.screen == FoundationScreen.REGISTER -> FoundationScreen.LOGIN
    state.screen == FoundationScreen.LOGIN -> FoundationScreen.CONNECT
    state.screen == FoundationScreen.CONNECT && state.repository.session != null -> FoundationScreen.WORKSPACE
    else -> FoundationScreen.WELCOME
}

@Composable
internal fun OnboardingProgress(step: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Подключение сервера", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("0${step.coerceIn(1, 5)} / 05", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(5) { index -> Box(Modifier.weight(1f).height(3.dp).background(if (index < step) MaterialTheme.colorScheme.primary else onboardingTone(0xFFE9EDF5, 0xFF364156), CircleShape)) }
        }
    }
}
