package com.tremendoushand.repeatodo.ui.tasks

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tremendoushand.repeatodo.AppContainer
import com.tremendoushand.repeatodo.data.AppSettings
import com.tremendoushand.repeatodo.data.CategoryEntity
import com.tremendoushand.repeatodo.data.TaskEntity
import com.tremendoushand.repeatodo.domain.LAST_DAY
import com.tremendoushand.repeatodo.domain.RepeatType
import com.tremendoushand.repeatodo.domain.WeekDays
import com.tremendoushand.repeatodo.ui.components.ColorDot
import com.tremendoushand.repeatodo.ui.components.TimePickerDialog
import com.tremendoushand.repeatodo.ui.components.appViewModel
import com.tremendoushand.repeatodo.ui.components.dayLabel
import com.tremendoushand.repeatodo.ui.components.formatTime
import com.tremendoushand.repeatodo.ui.components.weekOrder
import com.tremendoushand.repeatodo.ui.components.weekendColor
import com.tremendoushand.repeatodo.ui.theme.NoCategoryColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

enum class MonthMode { DAY, LAST, ANY }

data class EditForm(
    val loaded: Boolean = false,
    val original: TaskEntity? = null,
    val title: String = "",
    val note: String = "",
    val categoryId: String? = null,
    val type: RepeatType = RepeatType.DAILY,
    val weekAnyDay: Boolean = false,
    val weekDays: Int = WeekDays.bit(LocalDate.now().dayOfWeek),
    val monthMode: MonthMode = MonthMode.DAY,
    val monthDay: Int = LocalDate.now().dayOfMonth,
    val reminderMinutes: Int? = null,
    val targetCount: Int = 1,
    /** "Any time" rules: on how many different days of the week/month. */
    val periodTimes: Int = 1,
    val totalCount: Int? = null,
    val startDate: LocalDate = LocalDate.now(),
    val showInCalendar: Boolean = false,
    val error: String? = null,
) {
    val isNew get() = original == null

    val flexible: Boolean
        get() = (type == RepeatType.WEEKLY && weekAnyDay) || (type == RepeatType.MONTHLY && monthMode == MonthMode.ANY)

    /** Allowed days per period for the chosen rule. */
    val periodRange: IntRange get() = if (type == RepeatType.WEEKLY) 1..7 else 1..28
}

class TaskEditViewModel(
    private val c: AppContainer,
    private val taskId: String?,
    private val initialCategoryId: String? = null,
) : ViewModel() {
    private val _form = MutableStateFlow(EditForm(loaded = taskId == null))
    val form: StateFlow<EditForm> = _form.asStateFlow()
    val categories = c.tasks.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    init {
        viewModelScope.launch {
            val task = taskId?.let { c.tasks.task(it) }
            if (task != null) {
                _form.value = EditForm(
                    loaded = true,
                    original = task,
                    title = task.title,
                    note = task.note,
                    categoryId = task.categoryId,
                    type = task.repeatType,
                    weekAnyDay = task.repeatType == RepeatType.WEEKLY && task.weekDays and WeekDays.ALL == 0,
                    weekDays = if (task.weekDays and WeekDays.ALL == 0) WeekDays.bit(LocalDate.now().dayOfWeek) else task.weekDays,
                    monthMode = when {
                        task.monthDay == 0 -> MonthMode.ANY
                        task.monthDay >= LAST_DAY -> MonthMode.LAST
                        else -> MonthMode.DAY
                    },
                    monthDay = task.monthDay.takeIf { it in 1..31 } ?: LocalDate.now().dayOfMonth,
                    reminderMinutes = task.reminderMinutes,
                    targetCount = task.targetCount,
                    periodTimes = task.periodTimes,
                    totalCount = task.totalCount,
                    startDate = LocalDate.ofEpochDay(task.startDate),
                    showInCalendar = task.showInCalendar,
                )
            } else {
                // New tasks show in the calendar by default once calendar sync is on.
                // and start in the category picked on the task list, if any.
                val categoryId = initialCategoryId?.takeIf { id -> c.tasks.categories.first().any { it.id == id } }
                _form.update { it.copy(loaded = true, categoryId = categoryId, showInCalendar = c.settings.current().calendarEnabled) }
            }
        }
    }

    fun edit(change: (EditForm) -> EditForm) = _form.update { change(it).copy(error = null) }

    fun save(onSaved: () -> Unit) {
        val f = _form.value
        if (f.title.isBlank()) return _form.update { it.copy(error = "제목을 입력해 주세요.") }
        if (f.type == RepeatType.WEEKLY && !f.weekAnyDay && f.weekDays and WeekDays.ALL == 0) {
            return _form.update { it.copy(error = "요일을 하나 이상 골라 주세요.") }
        }
        val base = f.original ?: TaskEntity(id = "", title = "", startDate = 0, createdAt = 0, updatedAt = 0)
        val task = base.copy(
            title = f.title.trim(),
            note = f.note.trim(),
            categoryId = f.categoryId,
            repeatType = f.type,
            weekDays = if (f.type == RepeatType.WEEKLY && !f.weekAnyDay) f.weekDays else 0,
            monthDay = when {
                f.type != RepeatType.MONTHLY -> 0
                f.monthMode == MonthMode.ANY -> 0
                f.monthMode == MonthMode.LAST -> LAST_DAY
                else -> f.monthDay.coerceIn(1, 31)
            },
            reminderMinutes = f.reminderMinutes,
            targetCount = f.targetCount.coerceIn(1, 99),
            periodTimes = if (f.flexible) f.periodTimes.coerceIn(f.periodRange) else 1,
            totalCount = f.totalCount?.coerceIn(1, 9999),
            startDate = f.startDate.toEpochDay(),
            showInCalendar = f.showInCalendar,
        )
        viewModelScope.launch {
            c.tasks.saveTask(task)
            onSaved()
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = _form.value.original?.id ?: return onDone()
        viewModelScope.launch {
            c.tasks.deleteTask(id)
            onDone()
        }
    }

    fun restart(onDone: () -> Unit) {
        val id = _form.value.original?.id ?: return
        viewModelScope.launch {
            // Restarting a finished task counts from today with a fresh total.
            c.tasks.task(id)?.let { c.tasks.saveTask(it.copy(archived = false, startDate = LocalDate.now().toEpochDay(), totalCount = null)) }
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditScreen(taskId: String?, categoryId: String? = null, onDone: () -> Unit) {
    val vm = appViewModel(key = "edit-$taskId") { TaskEditViewModel(it, taskId, categoryId) }
    val form by vm.form.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (form.isNew) "할 일 추가" else "할 일 편집") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = {
                    if (!form.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "삭제") }
                    TextButton(onClick = { vm.save(onDone) }) { Text("저장") }
                },
            )
        },
    ) { padding ->
        if (!form.loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (form.original?.archived == true) {
                OutlinedButton(onClick = { vm.restart(onDone) }, modifier = Modifier.fillMaxWidth()) {
                    Text("끝난 할 일이에요. 오늘부터 다시 시작하기 (총 횟수 없이)")
                }
            }
            OutlinedTextField(
                value = form.title,
                onValueChange = { v -> vm.edit { it.copy(title = v) } },
                label = { Text("제목") },
                singleLine = true,
                isError = form.error != null && form.title.isBlank(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.note,
                onValueChange = { v -> vm.edit { it.copy(note = v) } },
                label = { Text("메모 (선택)") },
                modifier = Modifier.fillMaxWidth(),
            )
            CategoryPicker(categories, form.categoryId) { id -> vm.edit { it.copy(categoryId = id) } }

            HorizontalDivider()
            SectionTitle("반복")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val types = RepeatType.entries
                types.forEachIndexed { i, type ->
                    SegmentedButton(
                        selected = form.type == type,
                        onClick = { vm.edit { it.copy(type = type) } },
                        shape = SegmentedButtonDefaults.itemShape(i, types.size),
                    ) {
                        Text(
                            when (type) {
                                RepeatType.DAILY -> "매일"
                                RepeatType.WEEKLY -> "매주"
                                RepeatType.MONTHLY -> "매월"
                            },
                        )
                    }
                }
            }
            when (form.type) {
                RepeatType.DAILY -> Unit
                RepeatType.WEEKLY -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !form.weekAnyDay, onClick = { vm.edit { it.copy(weekAnyDay = false) } }, label = { Text("요일 지정") })
                        FilterChip(selected = form.weekAnyDay, onClick = { vm.edit { it.copy(weekAnyDay = true) } }, label = { Text("주중 아무 때나") })
                    }
                    if (form.weekAnyDay) {
                        PeriodDays(form, settings.weekStart) { v -> vm.edit { it.copy(periodTimes = v) } }
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            weekOrder(settings.weekStart).forEach { day ->
                                val selected = WeekDays.contains(form.weekDays, day)
                                FilterChip(
                                    selected = selected,
                                    onClick = { vm.edit { it.copy(weekDays = it.weekDays xor WeekDays.bit(day)) } },
                                    label = {
                                        Text(dayLabel(day), color = weekendColor(day).takeIf { !selected } ?: LocalContentColor.current)
                                    },
                                )
                            }
                        }
                    }
                }
                RepeatType.MONTHLY -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = form.monthMode == MonthMode.DAY, onClick = { vm.edit { it.copy(monthMode = MonthMode.DAY) } }, label = { Text("날짜 지정") })
                        FilterChip(selected = form.monthMode == MonthMode.LAST, onClick = { vm.edit { it.copy(monthMode = MonthMode.LAST) } }, label = { Text("말일") })
                        FilterChip(selected = form.monthMode == MonthMode.ANY, onClick = { vm.edit { it.copy(monthMode = MonthMode.ANY) } }, label = { Text("월중 아무 때나") })
                    }
                    if (form.monthMode == MonthMode.ANY) {
                        PeriodDays(form, settings.weekStart) { v -> vm.edit { it.copy(periodTimes = v) } }
                    }
                    if (form.monthMode == MonthMode.DAY) {
                        Stepper("매월", form.monthDay, "일", 1..31) { v -> vm.edit { it.copy(monthDay = v) } }
                        if (form.monthDay > 28) {
                            Hint("${form.monthDay}일이 없는 달에는 그 달 마지막 날에 해요.")
                        }
                    }
                }
            }

            HorizontalDivider()
            SectionTitle("알림")
            SwitchRow(
                title = "알림 시간 정하기",
                subtitle = form.reminderMinutes?.let { formatTime(it) }
                    ?: "끄면 설정의 ‘모아서 알림’ 시간에 다른 할 일과 함께 알려줘요.",
                checked = form.reminderMinutes != null,
                onChange = { on -> if (on) pickTime = true else vm.edit { it.copy(reminderMinutes = null) } },
            )
            if (form.reminderMinutes != null) {
                OutlinedButton(onClick = { pickTime = true }) { Text("시간 바꾸기") }
            }

            HorizontalDivider()
            SectionTitle("횟수 (선택)")
            if (form.flexible) {
                Stepper("하는 날마다", form.targetCount, "회", 1..99) { v -> vm.edit { it.copy(targetCount = v) } }
                Hint("예: 묵주기도 일주일에 2일, 하는 날마다 5단. 그날 5회를 채우면 1일로 세요.")
            } else {
                Stepper("한 번에", form.targetCount, "회", 1..99) { v -> vm.edit { it.copy(targetCount = v) } }
                Hint("예: 물 마시기 하루 8회. 탭할 때마다 1회씩 올라가요.")
            }
            SwitchRow(
                title = "총 반복 횟수 정하기",
                subtitle = "다 채우면 할 일이 ‘끝난 할 일’로 옮겨져요.",
                checked = form.totalCount != null,
                onChange = { on -> vm.edit { it.copy(totalCount = if (on) 30 else null) } },
            )
            form.totalCount?.let { total ->
                Stepper("총", total, "번", 1..9999) { v -> vm.edit { it.copy(totalCount = v) } }
            }

            HorizontalDivider()
            SectionTitle("기타")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("시작일", Modifier.weight(1f))
                OutlinedButton(onClick = { pickDate = true }) {
                    Text("${form.startDate.year}.${form.startDate.monthValue}.${form.startDate.dayOfMonth}")
                }
            }
            SwitchRow(
                title = "캘린더에 표시",
                subtitle = if (settings.calendarEnabled) "기기 캘린더에 반복 일정으로 보여줘요." else "설정에서 캘린더 연동을 켜면 표시돼요.",
                checked = form.showInCalendar,
                onChange = { on -> vm.edit { it.copy(showInCalendar = on) } },
            )

            form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { vm.save(onDone) }, modifier = Modifier.fillMaxWidth()) { Text("저장") }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (pickTime) {
        TimePickerDialog(
            initialMinutes = form.reminderMinutes ?: (9 * 60),
            onDismiss = { pickTime = false },
            onConfirm = { m -> vm.edit { it.copy(reminderMinutes = m) }; pickTime = false },
        )
    }
    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = form.startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        vm.edit { it.copy(startDate = date) }
                    }
                    pickDate = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("취소") } },
        ) { DatePicker(state = state) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("할 일을 삭제할까요?") },
            text = { Text("‘${form.title}’을(를) 삭제하면 대시보드의 기록에서도 사라져요.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onDone) }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

/** How many different days of the week/month an "any time" task needs. */
@Composable
private fun PeriodDays(form: EditForm, weekStart: DayOfWeek, onChange: (Int) -> Unit) {
    val weekly = form.type == RepeatType.WEEKLY
    val week = "${dayLabel(weekStart)}요일부터 ${dayLabel(weekStart.minus(1))}요일 사이"
    Stepper(if (weekly) "일주일에" else "한 달에", form.periodTimes.coerceIn(form.periodRange), "일", form.periodRange, onChange)
    Hint(
        if (form.periodTimes <= 1) {
            if (weekly) "$week 아무 날이나 한 번 하면 돼요." else "그 달 안에 아무 날이나 한 번 하면 돼요."
        } else {
            (if (weekly) week else "그 달 안에") + " 서로 다른 ${form.periodTimes}일에 하면 돼요."
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Hint(subtitle)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Stepper(prefix: String, value: Int, suffix: String, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(prefix, Modifier.width(84.dp))
        IconButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first) {
            Icon(Icons.Filled.Remove, "줄이기")
        }
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { s -> s.filter(Char::isDigit).take(4).toIntOrNull()?.let { onChange(it.coerceIn(range)) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(88.dp),
        )
        IconButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last) {
            Icon(Icons.Filled.Add, "늘리기")
        }
        Text(suffix)
    }
}

@Composable
private fun CategoryPicker(categories: List<CategoryEntity>, selected: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = categories.firstOrNull { it.id == selected }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            ColorDot(current?.color ?: NoCategoryColor)
            Spacer(Modifier.width(8.dp))
            Text(current?.name ?: "카테고리 없음", Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("카테고리 없음") },
                leadingIcon = { ColorDot(NoCategoryColor) },
                onClick = { onSelect(null); open = false },
            )
            categories.forEach { cat ->
                DropdownMenuItem(
                    text = { Text(cat.name) },
                    leadingIcon = { ColorDot(cat.color) },
                    onClick = { onSelect(cat.id); open = false },
                )
            }
        }
    }
}
