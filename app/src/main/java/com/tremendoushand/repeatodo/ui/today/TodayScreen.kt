package com.tremendoushand.repeatodo.ui.today

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tremendoushand.repeatodo.AppContainer
import com.tremendoushand.repeatodo.data.CategoryEntity
import com.tremendoushand.repeatodo.data.TaskEntity
import com.tremendoushand.repeatodo.domain.Occurrence
import com.tremendoushand.repeatodo.domain.RepeatType
import com.tremendoushand.repeatodo.domain.doneDays
import com.tremendoushand.repeatodo.domain.isDone
import com.tremendoushand.repeatodo.domain.occurrenceOn
import com.tremendoushand.repeatodo.domain.streak
import com.tremendoushand.repeatodo.ui.components.appViewModel
import com.tremendoushand.repeatodo.ui.components.dayLabel
import com.tremendoushand.repeatodo.ui.components.formatTime
import com.tremendoushand.repeatodo.ui.theme.NoCategoryColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class TodayItem(
    val task: TaskEntity,
    val category: CategoryEntity?,
    val occurrence: Occurrence,
    /** Times done on the shown day. */
    val count: Int,
    /** For "any time" tasks: days done in the week/month so far. */
    val doneDays: Int,
    /** The whole occurrence (the day, or the week/month for "any time" tasks) is finished. */
    val occurrenceDone: Boolean,
    val streak: Int,
) {
    val dayDone: Boolean get() = count >= task.targetCount

    /** Nothing more to do on the shown day. */
    val done: Boolean get() = dayDone || occurrenceDone
}

data class TodayState(
    val date: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val dated: List<TodayItem> = emptyList(),
    val thisWeek: List<TodayItem> = emptyList(),
    val thisMonth: List<TodayItem> = emptyList(),
    val hasTasks: Boolean = true,
) {
    val all get() = dated + thisWeek + thisMonth
    val isToday get() = date == today
}

/** Emits today's date, and again whenever the day changes. */
fun currentDateFlow() = flow {
    while (true) {
        emit(LocalDate.now())
        delay(30_000)
    }
}.distinctUntilChanged()

class TodayViewModel(private val c: AppContainer) : ViewModel() {
    /** The day being looked at, or null to follow today. */
    private val selected = MutableStateFlow<LocalDate?>(null)

    private val dates = combine(currentDateFlow(), selected) { today, sel ->
        today to (sel?.takeIf { !it.isAfter(today) } ?: today)
    }

    val state = combine(c.tasks.tasks, c.tasks.categories, c.tasks.doneCompletions, dates) { tasks, categories, completions, (today, date) ->
        val byCategory = categories.associateBy { it.id }
        val counts = completions.groupBy { it.taskId }.mapValues { (_, list) -> list.associate { it.occurrenceKey to it.count } }
        val items = tasks.mapNotNull { task ->
            val rule = task.rule
            if (date.isBefore(rule.startDate)) return@mapNotNull null
            val occ = rule.occurrenceOn(date) ?: return@mapNotNull null
            val taskCounts = counts[task.id].orEmpty()
            val countOn = { day: Long -> taskCounts[day] ?: 0 }
            val dayCount = countOn(date.toEpochDay())
            // Finished tasks only show on days they were worked on.
            if (task.archived && dayCount == 0) return@mapNotNull null
            val goal = task.goal
            TodayItem(
                task = task,
                category = task.categoryId?.let { byCategory[it] },
                occurrence = occ,
                count = dayCount,
                doneDays = if (occ.flexible) rule.doneDays(occ, countOn, goal) else 0,
                occurrenceDone = rule.isDone(occ, countOn, goal),
                streak = if (date == today) rule.streak({ rule.isDone(it, countOn, goal) }, today) else 0,
            )
        }.sortedWith(compareBy<TodayItem>({ it.done }, { it.task.reminderMinutes ?: Int.MAX_VALUE }, { it.task.sortOrder }))
        TodayState(
            date = date,
            today = today,
            dated = items.filter { !it.occurrence.flexible },
            thisWeek = items.filter { it.occurrence.flexible && it.task.repeatType == RepeatType.WEEKLY },
            thisMonth = items.filter { it.occurrence.flexible && it.task.repeatType == RepeatType.MONTHLY },
            hasTasks = tasks.any { !it.archived },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    /** Records one more time on the shown day (or clears that day once it's complete). */
    fun tap(item: TodayItem) {
        val day = state.value.date.toEpochDay()
        viewModelScope.launch { c.tasks.tap(item.task.id, day) }
    }

    fun showDate(date: LocalDate) {
        selected.value = if (date.isBefore(LocalDate.now())) date else null
    }

    fun move(days: Long) = showDate(state.value.date.plusDays(days))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val vm = appViewModel { TodayViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var pickDate by remember { mutableStateOf(false) }
    val date = state.date

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.clickable(onClickLabel = "날짜 고르기") { pickDate = true }) {
                        Text(if (state.isToday) "오늘" else "지난 기록", fontWeight = FontWeight.Bold)
                        Text(
                            "${date.monthValue}월 ${date.dayOfMonth}일 ${dayLabel(date.dayOfWeek)}요일",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.isToday) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                actions = {
                    if (!state.isToday) {
                        AssistChip(onClick = { vm.showDate(state.today) }, label = { Text("오늘로") })
                    }
                    IconButton(onClick = { vm.move(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "전날") }
                    IconButton(onClick = { pickDate = true }) { Icon(Icons.Outlined.CalendarMonth, "날짜 고르기") }
                    IconButton(onClick = { vm.move(1) }, enabled = !state.isToday) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음날")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "할 일 추가") }
        },
    ) { padding ->
        if (!state.hasTasks) {
            EmptyToday(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.isToday) {
                item {
                    Text(
                        "지난 날의 기록을 고치고 있어요. 탭하면 이날 한 것으로 기록돼요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            item { ProgressHeader(state) }
            section(if (state.isToday) "오늘" else "이날", state.dated, vm::tap, onOpen)
            section("이번 주 안에", state.thisWeek, vm::tap, onOpen)
            section("이번 달 안에", state.thisMonth, vm::tap, onOpen)
            if (state.all.isEmpty()) {
                item {
                    Text(
                        if (state.isToday) "오늘은 할 일이 없어요. 푹 쉬세요!" else "이날은 할 일이 없었어요.",
                        modifier = Modifier.padding(vertical = 32.dp).fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (pickDate) {
        val utc = { d: LocalDate -> d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        val todayMillis = utc(state.today)
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = utc(date),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        vm.showDate(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    pickDate = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("취소") } },
        ) { DatePicker(state = pickerState) }
    }
}

private fun LazyListScope.section(
    title: String,
    items: List<TodayItem>,
    onTap: (TodayItem) -> Unit,
    onOpen: (String) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "header-$title") {
        Text(
            "$title  ${items.count { it.done }}/${items.size}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
    }
    items(items, key = { "$title-${it.task.id}" }) { item ->
        TaskRow(item, onTap = { onTap(item) }, onLongPress = { onOpen(item.task.id) })
    }
}

@Composable
private fun ProgressHeader(state: TodayState) {
    val all = state.all
    val done = all.count { it.done }
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (all.isNotEmpty() && done == all.size) "모두 끝냈어요! 🎉" else "${all.size}개 중 ${done}개 완료",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                // Multi-count tasks fill the bar part way as they progress.
                progress = {
                    if (all.isEmpty()) 0f
                    else all.sumOf { if (it.done) 1.0 else it.count.toDouble() / it.task.targetCount }.toFloat() / all.size
                },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(item: TodayItem, onTap: () -> Unit, onLongPress: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val color = Color(item.category?.color ?: NoCategoryColor)
    val container by animateColorAsState(
        if (item.done) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "row",
    )
    Card(
        modifier = Modifier.fillMaxWidth().clip(CardDefaults.shape).combinedClickable(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onTap()
            },
            onLongClick = onLongPress,
            onClickLabel = "완료 표시",
            onLongClickLabel = "편집",
        ),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(36.dp).background(color, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                val meta = buildList {
                    if (item.occurrence.flexible) {
                        val period = if (item.task.repeatType == RepeatType.WEEKLY) "이번 주" else "이번 달"
                        add("$period ${item.doneDays}/${item.task.goal.daysPerPeriod}일" + if (item.occurrenceDone) " 달성" else "")
                    }
                    item.category?.let { add(it.name) }
                    item.task.reminderMinutes?.let { add(formatTime(it)) }
                    if (item.streak >= 2) add("🔥 ${item.streak}번 연속")
                }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            CheckCircle(item, color)
        }
    }
}

@Composable
private fun CheckCircle(item: TodayItem, color: Color) {
    val size = 36.dp
    when {
        item.dayDone -> Box(Modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
            if (item.task.targetCount > 1) {
                Text("${item.count}", style = MaterialTheme.typography.labelMedium, color = Color.White)
            } else {
                Icon(Icons.Filled.Check, contentDescription = "완료됨", tint = Color.White)
            }
        }
        // The week/month is already met; today wasn't needed.
        item.occurrenceDone && item.count == 0 -> Box(Modifier.size(size).border(2.dp, color, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = "기간 목표 달성", tint = color)
        }
        else -> Box(Modifier.size(size).border(2.dp, color, CircleShape), contentAlignment = Alignment.Center) {
            if (item.task.targetCount > 1) {
                Text("${item.count}/${item.task.targetCount}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun EmptyToday(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🗓️", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text("아직 할 일이 없어요", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "오른쪽 아래 + 버튼으로 매일, 매주, 매월 반복할 일을 추가해 보세요.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
