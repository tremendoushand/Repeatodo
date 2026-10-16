package com.tremendoushand.repeatodo.ui.today

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.tremendoushand.repeatodo.domain.occurrenceOn
import com.tremendoushand.repeatodo.domain.streak
import com.tremendoushand.repeatodo.ui.components.appViewModel
import com.tremendoushand.repeatodo.ui.components.dayLabel
import com.tremendoushand.repeatodo.ui.components.formatTime
import com.tremendoushand.repeatodo.ui.theme.NoCategoryColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TodayItem(
    val task: TaskEntity,
    val category: CategoryEntity?,
    val occurrence: Occurrence,
    val count: Int,
    val streak: Int,
) {
    val done: Boolean get() = count >= task.targetCount
}

data class TodayState(
    val date: LocalDate = LocalDate.now(),
    val today: List<TodayItem> = emptyList(),
    val thisWeek: List<TodayItem> = emptyList(),
    val thisMonth: List<TodayItem> = emptyList(),
    val hasTasks: Boolean = true,
) {
    val all get() = today + thisWeek + thisMonth
}

/** Emits today's date, and again whenever the day changes. */
fun currentDateFlow() = flow {
    while (true) {
        emit(LocalDate.now())
        delay(30_000)
    }
}.distinctUntilChanged()

class TodayViewModel(private val c: AppContainer) : ViewModel() {
    val state = combine(c.tasks.tasks, c.tasks.categories, c.tasks.doneCompletions, currentDateFlow()) { tasks, categories, completions, today ->
        val byCategory = categories.associateBy { it.id }
        val counts = completions.groupBy { it.taskId }.mapValues { (_, list) -> list.associate { it.occurrenceKey to it.count } }
        val items = tasks.filter { !it.archived }.mapNotNull { task ->
            val occ = task.rule.occurrenceOn(today) ?: return@mapNotNull null
            val taskCounts = counts[task.id].orEmpty()
            TodayItem(
                task = task,
                category = task.categoryId?.let { byCategory[it] },
                occurrence = occ,
                count = taskCounts[occ.key] ?: 0,
                streak = task.rule.streak({ (taskCounts[it] ?: 0) >= task.targetCount }, today),
            )
        }.sortedWith(compareBy<TodayItem>({ it.done }, { it.task.reminderMinutes ?: Int.MAX_VALUE }, { it.task.sortOrder }))
        TodayState(
            date = today,
            today = items.filter { !it.occurrence.flexible },
            thisWeek = items.filter { it.occurrence.flexible && it.task.repeatType == RepeatType.WEEKLY },
            thisMonth = items.filter { it.occurrence.flexible && it.task.repeatType == RepeatType.MONTHLY },
            hasTasks = tasks.any { !it.archived },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    fun tap(item: TodayItem) {
        viewModelScope.launch { c.tasks.tap(item.task.id, item.occurrence.key) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val vm = appViewModel { TodayViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val date = state.date

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("오늘", fontWeight = FontWeight.Bold)
                        Text(
                            "${date.monthValue}월 ${date.dayOfMonth}일 ${dayLabel(date.dayOfWeek)}요일",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { ProgressHeader(state) }
            section("오늘", state.today, vm::tap, onOpen)
            section("이번 주 안에", state.thisWeek, vm::tap, onOpen)
            section("이번 달 안에", state.thisMonth, vm::tap, onOpen)
            if (state.all.isEmpty()) {
                item {
                    Text(
                        "오늘은 할 일이 없어요. 푹 쉬세요!",
                        modifier = Modifier.padding(vertical = 32.dp).fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
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
                    else all.sumOf { it.count.coerceAtMost(it.task.targetCount).toDouble() / it.task.targetCount }.toFloat() / all.size
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
    if (item.done) {
        Box(Modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = "완료됨", tint = Color.White)
        }
    } else {
        Box(Modifier.size(size).border(2.dp, color, CircleShape), contentAlignment = Alignment.Center) {
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
