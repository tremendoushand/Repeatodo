package com.tremendoushand.repeatodo.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tremendoushand.repeatodo.AppContainer
import com.tremendoushand.repeatodo.data.CategoryEntity
import com.tremendoushand.repeatodo.data.TaskEntity
import com.tremendoushand.repeatodo.domain.Rate
import com.tremendoushand.repeatodo.domain.SlotState
import com.tremendoushand.repeatodo.domain.isDueOn
import com.tremendoushand.repeatodo.domain.occurrenceOn
import com.tremendoushand.repeatodo.domain.occurrencesBetween
import com.tremendoushand.repeatodo.domain.rateBetween
import com.tremendoushand.repeatodo.domain.slotState
import com.tremendoushand.repeatodo.domain.weekStartOf
import com.tremendoushand.repeatodo.ui.components.ColorDot
import com.tremendoushand.repeatodo.ui.components.appViewModel
import com.tremendoushand.repeatodo.ui.components.dayLabel
import com.tremendoushand.repeatodo.ui.theme.NoCategoryColor
import com.tremendoushand.repeatodo.ui.today.currentDateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import kotlin.math.roundToInt

enum class DashMode { WEEK, MONTH }

data class DashRow(
    val task: TaskEntity,
    val category: CategoryEntity?,
    /** One entry per day of the week view; null where the task isn't due. */
    val cells: List<SlotState?>,
    /** States of "any time" occurrences in the period, shown instead of [cells]. */
    val flexible: List<SlotState>,
    val rate: Rate,
    /** Times done on each day of the week view (same order as [cells]); only shown for multi-count tasks. */
    val dayCounts: List<Int> = emptyList(),
    /** Times done across the period. */
    val amount: Int = 0,
    /** Times needed across the occurrences counted so far. */
    val amountTarget: Int = 0,
) {
    val multiCount: Boolean get() = task.targetCount > 1
}

data class DayCell(val date: LocalDate, val done: Int, val due: Int)

data class DashState(
    val mode: DashMode = DashMode.WEEK,
    val from: LocalDate = weekStartOf(LocalDate.now()),
    val to: LocalDate = weekStartOf(LocalDate.now()).plusDays(6),
    val today: LocalDate = LocalDate.now(),
    val rows: List<DashRow> = emptyList(),
    val days: List<DayCell> = emptyList(),
    val overall: Rate = Rate.ZERO,
    val byCategory: List<Pair<CategoryEntity?, Rate>> = emptyList(),
)

private data class View(val mode: DashMode, val anchor: LocalDate)

class DashboardViewModel(c: AppContainer) : ViewModel() {
    private val view = MutableStateFlow(View(DashMode.WEEK, LocalDate.now()))

    val state = combine(c.tasks.tasks, c.tasks.categories, c.tasks.doneCompletions, currentDateFlow(), view) { tasks, categories, completions, today, v ->
        val (from, to) = when (v.mode) {
            DashMode.WEEK -> weekStartOf(v.anchor).let { it to it.plusDays(6) }
            DashMode.MONTH -> v.anchor.withDayOfMonth(1).let { it to it.withDayOfMonth(it.lengthOfMonth()) }
        }
        val byId = categories.associateBy { it.id }
        val counts = completions.groupBy { it.taskId }.mapValues { (_, list) -> list.associate { it.occurrenceKey to it.count } }
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

        val rows = tasks.mapNotNull { task ->
            val rule = task.rule
            if (rule.startDate.isAfter(to)) return@mapNotNull null
            val taskCounts = counts[task.id].orEmpty()
            val isDone = { key: Long -> (taskCounts[key] ?: 0) >= task.targetCount }
            val occurrences = rule.occurrencesBetween(from, to)
            if (occurrences.isEmpty()) return@mapNotNull null
            if (task.archived && occurrences.none { isDone(it.key) }) return@mapNotNull null
            DashRow(
                task = task,
                category = task.categoryId?.let { byId[it] },
                cells = if (rule.isFlexible) emptyList() else days.map { d ->
                    rule.occurrenceOn(d)?.let { slotState(it, isDone(it.key), today) }
                },
                flexible = if (rule.isFlexible) occurrences.map { slotState(it, isDone(it.key), today) } else emptyList(),
                rate = rule.rateBetween(from, to, today, isDone),
                dayCounts = if (rule.isFlexible) emptyList() else days.map { d -> taskCounts[d.toEpochDay()] ?: 0 },
                amount = occurrences.sumOf { taskCounts[it.key] ?: 0 },
                amountTarget = occurrences.count { !it.start.isAfter(today) } * task.targetCount,
            )
        }

        val dayCells = days.map { d ->
            var due = 0
            var done = 0
            for (row in rows) {
                val rule = row.task.rule
                if (!rule.isDueOn(d)) continue
                due++
                if ((counts[row.task.id]?.get(d.toEpochDay()) ?: 0) >= row.task.targetCount) done++
            }
            DayCell(d, done, due)
        }

        DashState(
            mode = v.mode, from = from, to = to, today = today,
            rows = rows.sortedWith(compareBy({ it.category?.sortOrder ?: Int.MAX_VALUE }, { it.task.sortOrder })),
            days = dayCells,
            overall = rows.fold(Rate.ZERO) { acc, r -> acc + r.rate },
            byCategory = rows.groupBy { it.category }
                .map { (cat, list) -> cat to list.fold(Rate.ZERO) { acc, r -> acc + r.rate } }
                .sortedBy { it.first?.sortOrder ?: Int.MAX_VALUE },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashState())

    fun setMode(mode: DashMode) {
        view.value = View(mode, LocalDate.now())
    }

    fun move(delta: Int) {
        val v = view.value
        view.value = v.copy(
            anchor = when (v.mode) {
                DashMode.WEEK -> v.anchor.plusWeeks(delta.toLong())
                DashMode.MONTH -> v.anchor.plusMonths(delta.toLong())
            },
        )
    }

    fun reset() {
        view.value = view.value.copy(anchor = LocalDate.now())
    }
}

private fun Rate.percentText(): String = ratio?.let { "${(it * 100).roundToInt()}%" } ?: "–"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val vm = appViewModel { DashboardViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("대시보드", fontWeight = FontWeight.Bold) }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    DashMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = state.mode == mode,
                            onClick = { vm.setMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, DashMode.entries.size),
                        ) { Text(if (mode == DashMode.WEEK) "주간" else "월간") }
                    }
                }
            }
            item { PeriodNavigator(state, onMove = vm::move, onReset = vm::reset) }
            item { SummaryCard(state) }
            if (state.rows.isEmpty()) {
                item {
                    Text(
                        "이 기간에 해당하는 할 일이 없어요.",
                        Modifier.fillMaxWidth().padding(24.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (state.mode == DashMode.WEEK) {
                item { WeekHeader(state) }
                items(state.rows, key = { it.task.id }) { WeekRow(it) }
            } else {
                item { MonthGrid(state) }
                item { Text("할 일별 달성률", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(state.rows, key = { it.task.id }) { RateRow(it) }
            }
        }
    }
}

@Composable
private fun PeriodNavigator(state: DashState, onMove: (Int) -> Unit, onReset: () -> Unit) {
    val title = when (state.mode) {
        DashMode.WEEK -> {
            val end = if (state.to.month == state.from.month) "${state.to.dayOfMonth}일" else "${state.to.monthValue}월 ${state.to.dayOfMonth}일"
            "${state.from.monthValue}월 ${state.from.dayOfMonth}일 – $end"
        }
        DashMode.MONTH -> "${state.from.year}년 ${state.from.monthValue}월"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = { onMove(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전") }
        Text(
            title,
            Modifier.weight(1f).clickable(onClickLabel = "오늘로", onClick = onReset),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
        IconButton(onClick = { onMove(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음") }
    }
}

@Composable
private fun SummaryCard(state: DashState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(state.overall.percentText(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(
                    "달성 (${state.overall.done}/${state.overall.counted})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(
                "오늘까지 해야 했던 일 중 끝낸 비율이에요. 여러 번 하는 일은 목표 횟수를 다 채워야 완료예요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.byCategory.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                state.byCategory.forEach { (cat, rate) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        ColorDot(cat?.color ?: NoCategoryColor)
                        Spacer(Modifier.width(8.dp))
                        Text(cat?.name ?: "카테고리 없음", Modifier.width(96.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(
                            progress = { rate.ratio ?: 0f },
                            modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Color(cat?.color ?: NoCategoryColor),
                        )
                        Text(rate.percentText(), Modifier.width(48.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private val NameWidth = 104.dp

@Composable
private fun WeekHeader(state: DashState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(NameWidth))
        state.days.forEach { cell ->
            val isToday = cell.date == state.today
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    dayLabel(cell.date.dayOfWeek),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${cell.date.dayOfMonth}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun WeekRow(row: DashRow) {
    val color = Color(row.category?.color ?: NoCategoryColor)
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(NameWidth).padding(end = 4.dp)) {
            Text(row.task.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (row.multiCount) {
                Text(
                    "${row.amount}/${row.amountTarget}회",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (row.flexible.isNotEmpty()) {
            val label = when {
                row.flexible.all { it == SlotState.DONE } -> "완료"
                row.flexible.any { it == SlotState.MISSED } -> "놓침"
                else -> "진행 중 (기간 내 아무 때나)"
            }
            Text(label, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = color)
        } else {
            row.cells.forEachIndexed { i, state ->
                val count = if (row.multiCount) row.dayCounts.getOrNull(i) ?: 0 else 0
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SlotMark(state, color, count) }
            }
        }
    }
}

/** One day's mark. Multi-count tasks show how many times they were done that day. */
@Composable
private fun SlotMark(state: SlotState?, color: Color, count: Int = 0) {
    val m = Modifier.size(22.dp)
    val label: @Composable (Color) -> Unit = { textColor ->
        if (count > 0) Text("$count", style = MaterialTheme.typography.labelSmall, color = textColor, maxLines = 1)
    }
    when (state) {
        SlotState.DONE -> Box(m.background(color, CircleShape), contentAlignment = Alignment.Center) { label(Color.White) }
        SlotState.MISSED -> Box(m.border(2.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
            label(MaterialTheme.colorScheme.error)
        }
        SlotState.PENDING -> Box(m.border(2.dp, color, CircleShape), contentAlignment = Alignment.Center) { label(color) }
        SlotState.UPCOMING -> Box(m.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
        null -> Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
    }
}

@Composable
private fun MonthGrid(state: DashState) {
    val lead = state.from.dayOfWeek.value - 1 // Monday first
    val cells: List<DayCell?> = List(lead) { null } + state.days
    val primary = MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                java.time.DayOfWeek.values().forEach {
                    Text(dayLabel(it), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(4.dp))
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    for (i in 0 until 7) {
                        val cell = week.getOrNull(i)
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                            if (cell != null) {
                                val closed = !cell.date.isAfter(state.today)
                                val ratio = if (cell.due == 0 || !closed) 0f else cell.done.toFloat() / cell.due
                                val bg = when {
                                    cell.due == 0 -> Color.Transparent
                                    !closed -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> primary.copy(alpha = 0.15f + 0.85f * ratio)
                                }
                                Box(
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(bg).let {
                                        if (cell.date == state.today) it.border(2.dp, primary, RoundedCornerShape(8.dp)) else it
                                    },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "${cell.date.dayOfMonth}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (closed && ratio > 0.5f) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "색이 진할수록 그날 할 일을 많이 끝낸 날이에요. ‘아무 때나’ 할 일은 아래 목록에서 확인하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RateRow(row: DashRow) {
    val color = Color(row.category?.color ?: NoCategoryColor)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDot(row.category?.color ?: NoCategoryColor)
            Spacer(Modifier.width(8.dp))
            Text(row.task.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${row.rate.done}/${row.rate.counted}  ${row.rate.percentText()}", style = MaterialTheme.typography.bodySmall)
        }
        if (row.multiCount) {
            Text(
                "누적 ${row.amount}회 · 목표 ${row.amountTarget}회 (한 번에 ${row.task.targetCount}회)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { row.rate.ratio ?: 0f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = color,
        )
    }
}
