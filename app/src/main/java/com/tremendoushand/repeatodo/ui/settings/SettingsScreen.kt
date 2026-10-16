package com.tremendoushand.repeatodo.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.identity.Identity
import com.tremendoushand.repeatodo.AppContainer
import com.tremendoushand.repeatodo.BuildConfig
import com.tremendoushand.repeatodo.calendar.DeviceCalendar
import com.tremendoushand.repeatodo.data.AppSettings
import com.tremendoushand.repeatodo.data.ThemeMode
import com.tremendoushand.repeatodo.domain.Snapshot
import com.tremendoushand.repeatodo.domain.SnapshotJson
import com.tremendoushand.repeatodo.notify.Notifications
import com.tremendoushand.repeatodo.sync.DriveAuth
import com.tremendoushand.repeatodo.ui.components.ColorDot
import com.tremendoushand.repeatodo.ui.components.TimePickerDialog
import com.tremendoushand.repeatodo.ui.components.appViewModel
import com.tremendoushand.repeatodo.ui.components.formatTime
import com.tremendoushand.repeatodo.ui.tasks.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.util.Date
import java.util.Locale

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val calendars = MutableStateFlow<List<DeviceCalendar>>(emptyList())
    val categories = c.tasks.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val syncing = MutableStateFlow(false)
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun canScheduleExact() = c.reminders.canScheduleExact()
    fun hasCalendarPermission() = c.calendar.hasPermission()

    fun refreshAfterResume() = viewModelScope.launch {
        c.reminders.rescheduleAll()
        if (c.calendar.hasPermission()) calendars.value = c.calendar.calendars()
    }

    fun addDigestTime(minutes: Int) = viewModelScope.launch {
        c.settings.setDigestTimes(settings.value.digestTimes + minutes)
        c.reminders.rescheduleAll()
    }

    fun removeDigestTime(minutes: Int) = viewModelScope.launch {
        c.settings.setDigestTimes(settings.value.digestTimes - minutes)
        c.reminders.rescheduleAll()
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { c.settings.setThemeMode(mode) }

    fun setWeekStart(day: DayOfWeek) = viewModelScope.launch {
        c.settings.setWeekStart(day)
        // "Any time this week" events start on the first day of the week.
        c.calendar.syncAll()
    }

    fun enableCalendar(calendarId: Long? = null) = viewModelScope.launch {
        val list = c.calendar.calendars()
        calendars.value = list
        // Prefer the account's own main calendar (its name matches the account).
        val id = calendarId ?: settings.value.calendarId?.takeIf { saved -> list.any { it.id == saved } }
            ?: list.firstOrNull { it.name == it.account }?.id ?: list.firstOrNull()?.id
        if (id == null) {
            _messages.send("기기에 쓸 수 있는 캘린더가 없어요.")
            return@launch
        }
        c.settings.setCalendar(true, id)
        c.calendar.syncAll()
    }

    fun setCategoryCalendar(categoryId: String, calendarId: Long?) = viewModelScope.launch {
        c.settings.setCategoryCalendar(categoryId, calendarId)
        c.calendar.syncAll()
    }

    /** Turns calendar sync off and deletes every event Repeatodo put in the device calendars. */
    fun clearCalendar() = viewModelScope.launch {
        c.settings.setCalendar(false, settings.value.calendarId)
        val removed = c.calendar.removeAll()
        _messages.send(if (removed > 0) "캘린더에서 Repeatodo 일정 ${removed}개를 지웠어요." else "캘린더에 지울 Repeatodo 일정이 없어요.")
    }

    fun onDriveConnected() = viewModelScope.launch {
        c.settings.setDriveConnected(true)
        c.sync.applySchedule()
        syncNow()
    }

    fun showMessage(message: String) = viewModelScope.launch {
        _messages.send(message)
    }

    fun disconnectDrive() = viewModelScope.launch {
        c.settings.setDriveConnected(false)
        c.sync.applySchedule()
        _messages.send("동기화를 껐어요. 드라이브에 저장된 데이터는 그대로 남아 있어요.")
    }

    fun setSyncInterval(hours: Int) = viewModelScope.launch {
        c.settings.setSyncInterval(hours)
        c.sync.applySchedule()
    }

    fun setWifiOnly(wifiOnly: Boolean) = viewModelScope.launch {
        c.settings.setSyncWifiOnly(wifiOnly)
        c.sync.applySchedule()
    }

    fun syncNow() = viewModelScope.launch {
        if (syncing.value) return@launch
        syncing.value = true
        try {
            c.sync.syncNow()
            _messages.send("동기화했어요.")
        } catch (e: Exception) {
            _messages.send("동기화하지 못했어요: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            syncing.value = false
        }
    }

    fun export(context: Context, uri: Uri) = viewModelScope.launch {
        runCatching {
            val json = SnapshotJson.encodeToString(Snapshot.serializer(), c.tasks.snapshot())
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
            }
        }.onSuccess { _messages.send("백업 파일을 저장했어요.") }
            .onFailure { _messages.send("저장하지 못했어요: ${it.message}") }
    }

    fun import(context: Context, uri: Uri) = viewModelScope.launch {
        runCatching {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            }
            c.tasks.mergeIn(SnapshotJson.decodeFromString(Snapshot.serializer(), text))
        }.onSuccess { _messages.send("백업을 불러왔어요. 기존 데이터와 합쳐졌어요.") }
            .onFailure { _messages.send("불러오지 못했어요. Repeatodo 백업 파일인지 확인해 주세요.") }
    }
}

private val intervals = listOf(0 to "수동", 1 to "1시간", 6 to "6시간", 12 to "12시간", 24 to "하루")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val vm = appViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var resumeTick by remember { mutableIntStateOf(0) }
    var addingTime by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleResumeEffect(Unit) {
        resumeTick++
        vm.refreshAfterResume()
        onPauseOrDispose { }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) vm.enableCalendar() else vm.showMessage("캘린더 권한이 있어야 일정을 표시할 수 있어요.")
    }
    var confirmClearCalendar by remember { mutableStateOf(false) }
    val clearCalendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) vm.clearCalendar() else vm.showMessage("캘린더 권한이 있어야 일정을 지울 수 있어요.")
    }
    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data) }
                .onSuccess { vm.onDriveConnected() }
                .onFailure { vm.showMessage("구글 계정을 연결하지 못했어요: ${it.message}") }
        } else {
            vm.showMessage("구글 계정 연결을 취소했어요.")
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(context, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(context, uri)
    }

    fun connectDrive() {
        scope.launch {
            runCatching { DriveAuth.authorize(context) }
                .onSuccess { r ->
                    val pi = r.pendingIntent
                    if (r.hasResolution() && pi != null) {
                        authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    } else {
                        vm.onDriveConnected()
                    }
                }
                .onFailure { vm.showMessage("구글 계정을 연결하지 못했어요. 구글 클라우드 설정을 확인해 주세요. (${it.message})") }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("설정", fontWeight = FontWeight.Bold) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Re-read permission states whenever the screen comes back.
        val tick = resumeTick
        val canNotify = remember(tick) { Notifications.canNotify(context) }
        val canExact = remember(tick) { vm.canScheduleExact() }

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("알림")
            if (!canNotify) {
                Warning("알림이 꺼져 있어 할 일을 알려드릴 수 없어요.") {
                    if (Build.VERSION.SDK_INT >= 33 && !s.askedNotificationPermission) {
                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startSafely(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                }
            }
            if (!canExact && Build.VERSION.SDK_INT >= 31) {
                Warning("정확한 시간 알림이 허용되지 않아 알림이 몇 분 늦을 수 있어요.") {
                    context.startSafely(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()),
                    )
                }
            }
            Text("모아서 알림 시간")
            Hint("알림 시간을 정하지 않은 할 일 중 아직 안 한 일을 이 시간에 한 번에 알려줘요.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.digestTimes.forEach { m ->
                    InputChip(
                        selected = false,
                        onClick = { vm.removeDigestTime(m) },
                        label = { Text(formatTime(m)) },
                        trailingIcon = { Text("✕", style = MaterialTheme.typography.labelSmall) },
                        colors = InputChipDefaults.inputChipColors(),
                    )
                }
                AssistChip(
                    onClick = { addingTime = true },
                    label = { Text("시간 추가") },
                    leadingIcon = { Icon(Icons.Filled.Add, null) },
                )
            }

            HorizontalDivider()
            Section("화면")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = ThemeMode.entries
                modes.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = s.themeMode == mode,
                        onClick = { vm.setTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) {
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> "시스템"
                                ThemeMode.LIGHT -> "밝게"
                                ThemeMode.DARK -> "어둡게"
                            },
                        )
                    }
                }
            }
            Text("한 주의 시작")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val days = listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY)
                days.forEachIndexed { i, day ->
                    SegmentedButton(
                        selected = s.weekStart == day,
                        onClick = { vm.setWeekStart(day) },
                        shape = SegmentedButtonDefaults.itemShape(i, days.size),
                    ) { Text(if (day == DayOfWeek.SUNDAY) "일요일" else "월요일") }
                }
            }
            Hint("대시보드 주간 화면, ‘주중 아무 때나’ 할 일의 한 주, 요일 고르기, 엑셀 달력이 이 요일부터 시작해요.")

            HorizontalDivider()
            Section("캘린더 연동")
            SwitchRow(
                title = "기기 캘린더에 할 일 표시",
                subtitle = "‘캘린더에 표시’를 켠 할 일을 반복 일정으로 등록해요. 끄면 등록한 일정을 지워요.",
                checked = s.calendarEnabled,
                onChange = { on ->
                    when {
                        !on -> confirmClearCalendar = true
                        vm.hasCalendarPermission() -> vm.enableCalendar()
                        else -> calendarLauncher.launch(
                            arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR),
                        )
                    }
                },
            )
            if (s.calendarEnabled) {
                Text("기본 캘린더")
                CalendarPicker(calendars, s.calendarId) { id -> if (id != null) vm.enableCalendar(id) }
                if (categories.isNotEmpty()) {
                    Text("카테고리별 캘린더")
                    Hint("카테고리마다 다른 캘린더에 넣을 수 있어요. 고르지 않으면 기본 캘린더에 들어가요.")
                    categories.forEach { cat ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ColorDot(cat.color)
                            Spacer(Modifier.width(8.dp))
                            Text(cat.name, Modifier.width(88.dp), maxLines = 1)
                            CalendarPicker(
                                calendars,
                                s.categoryCalendars[cat.id],
                                modifier = Modifier.weight(1f),
                                defaultLabel = "기본 캘린더",
                            ) { id -> vm.setCategoryCalendar(cat.id, id) }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { confirmClearCalendar = true }) { Text("캘린더에서 Repeatodo 일정 모두 지우기") }
            Hint("앱을 삭제하기 전에 눌러 주세요. 앱을 먼저 지우면 캘린더에 일정이 남아요.")

            HorizontalDivider()
            Section("구글 드라이브 동기화")
            if (!s.driveConnected) {
                Hint("구글 드라이브의 앱 전용 공간에 데이터를 저장해 폰을 바꾸거나 여러 기기에서 써도 그대로 이어져요. 다른 드라이브 파일에는 접근하지 않아요.")
                Button(onClick = ::connectDrive) { Text("구글 계정 연결") }
            } else {
                val last = s.lastSyncAt?.let { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(it)) }
                Text("연결됨" + (last?.let { " · 마지막 동기화 $it" } ?: ""))
                s.lastSyncError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("자동 동기화 주기")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    intervals.forEach { (hours, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = s.syncIntervalHours == hours,
                            onClick = { vm.setSyncInterval(hours) },
                            label = { Text(label) },
                        )
                    }
                }
                SwitchRow(
                    title = "Wi-Fi에서만 자동 동기화",
                    subtitle = if (s.syncWifiOnly) "모바일 데이터에서는 자동으로 동기화하지 않아요." else "모바일 데이터로도 동기화해요. (데이터 사용량은 아주 적어요)",
                    checked = s.syncWifiOnly,
                    onChange = vm::setWifiOnly,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.syncNow() }, enabled = !syncing) { Text(if (syncing) "동기화 중…" else "지금 동기화") }
                    OutlinedButton(onClick = { vm.disconnectDrive() }) { Text("연결 해제") }
                }
                if (s.lastSyncError != null) {
                    OutlinedButton(onClick = ::connectDrive) { Text("구글 계정 다시 연결") }
                }
            }

            HorizontalDivider()
            Section("데이터")
            Hint("데이터는 이 폰 안에 저장돼요. 파일로 백업해 두었다가 언제든 다시 불러올 수 있어요.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("repeatodo-backup.json") }) { Text("백업 파일 만들기") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("백업 불러오기") }
            }

            HorizontalDivider()
            Section("정보")
            val uri = LocalUriHandler.current
            Text(
                "사용자 가이드 보기",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { runCatching { uri.openUri(GUIDE_URL) } }.padding(vertical = 4.dp),
            )
            Text("Repeatodo ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
        }
    }

    if (addingTime) {
        TimePickerDialog(
            initialMinutes = 20 * 60,
            onDismiss = { addingTime = false },
            onConfirm = { vm.addDigestTime(it); addingTime = false },
        )
    }
    if (confirmClearCalendar) {
        AlertDialog(
            onDismissRequest = { confirmClearCalendar = false },
            title = { Text("캘린더 일정을 모두 지울까요?") },
            text = {
                Text(
                    "Repeatodo가 폰 캘린더에 만든 반복 일정을 모두 지우고 캘린더 연동을 꺼요. 할 일과 기록은 그대로 남아요.\n\n" +
                        "앱을 삭제하려면 이걸 먼저 해 주세요. 앱을 먼저 지우면 캘린더에 일정이 남아요.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearCalendar = false
                    if (vm.hasCalendarPermission()) {
                        vm.clearCalendar()
                    } else {
                        clearCalendarLauncher.launch(
                            arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR),
                        )
                    }
                }) { Text("모두 지우기", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClearCalendar = false }) { Text("취소") } },
        )
    }
}

/** Opens a system screen, ignoring phones that don't have it. */
private fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    }
}

private const val GUIDE_URL = "https://github.com/tremendoushand/Repeatodo/blob/main/docs/USER_GUIDE.md"

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Warning(text: String, onFix: () -> Unit) {
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onFix) { Text("허용") }
        }
    }
}

@Composable
private fun CalendarPicker(
    calendars: List<DeviceCalendar>,
    selected: Long?,
    modifier: Modifier = Modifier,
    /** When set, offers a "use the default" choice that reports null. */
    defaultLabel: String? = null,
    onSelect: (Long?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = calendars.firstOrNull { it.id == selected }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            current?.let { ColorDot(it.color.toLong() and 0xFFFFFFFFL) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(current?.name ?: defaultLabel ?: "캘린더 선택", maxLines = 1)
                current?.account?.takeIf { it != current.name }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (defaultLabel != null) {
                DropdownMenuItem(text = { Text(defaultLabel) }, onClick = { onSelect(null); open = false })
            }
            calendars.forEach { cal ->
                DropdownMenuItem(
                    text = { Text("${cal.name} (${cal.account})") },
                    leadingIcon = { ColorDot(cal.color.toLong() and 0xFFFFFFFFL) },
                    onClick = { onSelect(cal.id); open = false },
                )
            }
        }
    }
}
