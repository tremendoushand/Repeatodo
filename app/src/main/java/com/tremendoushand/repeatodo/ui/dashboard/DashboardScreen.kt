package com.tremendoushand.repeatodo.ui.dashboard

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tremendoushand.repeatodo.AppContainer
import com.tremendoushand.repeatodo.data.CategoryEntity
import com.tremendoushand.repeatodo.data.TaskEntity
import com.tremendoushand.repeatodo.domain.Occurrence
import com.tremendoushand.repeatodo.domain.Rate
import com.tremendoushand.repeatodo.domain.RepeatType
import com.tremendoushand.repeatodo.domain.SlotState
import com.tremendoushand.repeatodo.domain.daysOf
import com.tremendoushand.repeatodo.domain.doneDays
import com.tremendoushand.repeatodo.domain.isDone
import com.tremendoushand.repeatodo.domain.isDueOn
import com.tremendoushand.repeatodo.domain.occurrenceOn
import com.tremendoushand.repeatodo.domain.occurrencesBetween
import com.tremendoushand.repeatodo.domain.rateBetween
import com.tremendoushand.repeatodo.domain.slotState
import com.tremendoushand.repeatodo.domain.weekStartOf
import com.tremendoushand.repeatodo.export.Xlsx
import com.tremendoushand.repeatodo.export.buildMonthReport
import com.tremendoushand.repeatodo.ui.components.ColorDot
import com.tremendoushand.repeatodo.ui.components.appViewModel
import com.tremendoushand.repeatodo.ui.components.dayLabel
import com.tremendoushand.repeatodo.ui.components.repeatLabel
import com.tremendoushand.repeatodo.ui.components.weekOrder
import com.tremendoushand.repeatodo.ui.components.weekendColor
import com.tremendoushand.repeatodo.ui.theme.NoCategoryColor
import com.tremendoushand.repeatodo.ui.today.currentDateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

enum class DashMode { WEEK, MONTH }

/** Progress of an "any time" task in the week or month being looked at. */
data class PeriodProgress(val doneDays: Int, val needDays: Int, val state: SlotState, val monthly: Boolean)

data class DashRow(
    val task: TaskEntity,
    val category: CategoryEntity?,
    /**
     * One entry per day of the view. Dated tasks: the day's state, or null where the task isn't due.
     * "Any time" tasks: DONE or PENDING (started) on days it was worked on, null otherwise.
     */
    val cells: List<SlotState?>,
    /** Times done on each day of the view (same order as [cells]). */
    val dayCounts: List<Int>,
    val rate: Rate,
    /** Set for "any time" tasks. */
    val period: PeriodProgress? = null,
    /** Times done across the occurrences in view. */
    val amount: Int = 0,
    /** Times needed across the occurrences counted so far. */
    val amountTarget: Int = 0,
) {
    val multiCount: Boolean get() = task.targetCount > 1
}

/** A day of the month grid: dated tasks done/due, plus the colors of "any time" tasks worked on that day. */
data class DayCell(val date: LocalDate, val done: Int, val due: Int, val anyTime: List<Long> = emptyList())

data class DashState(
    val mode: DashMode = DashMode.WEEK,
    val from: LocalDate = weekStartOf(LocalDate.now()),
    val to: LocalDate = weekStartOf(LocalDate.now()).plusDays(6),
    val today: LocalDate = LocalDate.now(),
    val rows: List<DashRow> = emptyList(),
    val days: List<DayCell> = emptyList(),
    val overall: Rate = Rate.ZERO,
    val byCategory: List<Pair<CategoryEntity?, Rate>> = emptyList(),
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
)

private data class View(val mode: DashMode, val anchor: LocalDate)

class DashboardViewModel(private val c: AppContainer) : ViewModel() {
    private val view = MutableStateFlow(View(DashMode.WEEK, LocalDate.now()))

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val viewAndWeekStart = combine(view, c.settings.settings.map { it.weekStart }.distinctUntilChanged()) { v, ws -> v to ws }

    val state = combine(c.tasks.tasks, c.tasks.categories, c.tasks.doneCompletions, currentDateFlow(), viewAndWeekStart) { tasks, categories, completions, today, (v, weekStart) ->
        val (from, to) = when (v.mode) {
            DashMode.WEEK -> weekStartOf(v.anchor, weekStart).let { it to it.plusDays(6) }
            DashMode.MONTH -> v.anchor.withDayOfMonth(1).let { it to it.withDayOfMonth(it.lengthOfMonth()) }
        }
        val byId = categories.associateBy { it.id }
        val counts = completions.groupBy { it.taskId }.mapValues { (_, list) -> list.associate { it.occurrenceKey to it.count } }
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

        val rows = tasks.mapNotNull { task ->
            val rule = task.rule(weekStart)
            if (rule.startDate.isAfter(to)) return@mapNotNull null
            val taskCounts = counts[task.id].orEmpty()
            val countOn = { day: Long -> taskCounts[day] ?: 0 }
            val goal = task.goal
            val isDone = { occ: Occurrence -> rule.isDone(occ, countOn, goal) }
            val occurrences = rule.occurrencesBetween(from, to)
            if (occurrences.isEmpty()) return@mapNotNull null
            val dayCounts = days.map { countOn(it.toEpochDay()) }
            if (task.archived && dayCounts.all { it == 0 }) return@mapNotNull null
            val started = occurrences.count { !it.start.isAfter(today) }
            DashRow(
                task = task,
                category = task.categoryId?.let { byId[it] },
                cells = days.mapIndexed { i, d ->
                    if (rule.isFlexible) {
                        when {
                            d.isBefore(rule.startDate) -> null
                            dayCounts[i] >= goal.perDay -> SlotState.DONE
                            dayCounts[i] > 0 -> SlotState.PENDING
                            else -> null
                        }
                    } else {
                        rule.occurrenceOn(d)?.let { slotState(it, isDone(it), today) }
                    }
                },
                dayCounts = dayCounts,
                rate = rule.rateBetween(from, to, today, isDone),
                period = if (!rule.isFlexible) null else {
                    // The week/month that is open now, or the last one in view.
                    val focus = occurrences.lastOrNull { !it.start.isAfter(minOf(to, today)) } ?: occurrences.first()
                    PeriodProgress(
                        doneDays = rule.doneDays(focus, countOn, goal),
                        needDays = goal.daysPerPeriod,
                        state = slotState(focus, isDone(focus), today),
                        monthly = task.repeatType == RepeatType.MONTHLY,
                    )
                },
                amount = occurrences.sumOf { occ -> rule.daysOf(occ).sumOf { countOn(it.toEpochDay()) } },
                amountTarget = started * goal.perDay * goal.daysPerPeriod,
            )
        }

        val dayCells = days.mapIndexed { i, d ->
            var due = 0
            var done = 0
            val anyTime = mutableListOf<Long>()
            for (row in rows) {
                val rule = row.task.rule(weekStart)
                if (rule.isFlexible) {
                    if (row.dayCounts[i] > 0) anyTime += row.category?.color ?: NoCategoryColor
                    continue
                }
                if (!rule.isDueOn(d)) continue
                due++
                if (row.dayCounts[i] >= row.task.targetCount) done++
            }
            DayCell(d, done, due, anyTime)
        }

        DashState(
            mode = v.mode, from = from, to = to, today = today, weekStart = weekStart,
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

    /** Writes [month]'s records to [uri] as an Excel calendar. */
    fun exportMonth(context: Context, uri: Uri, month: YearMonth) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val weekStart = c.settings.current().weekStart
                val report = buildMonthReport(
                    month,
                    c.tasks.tasks.first(),
                    c.tasks.categories.first(),
                    c.tasks.allCompletions(),
                    weekStart,
                ) { repeatLabel(it, weekStart) }
                val resolver = context.contentResolver
                val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: resolver.openOutputStream(uri)
                checkNotNull(out) { "파일을 열 수 없어요" }.use { Xlsx.write(report, it) }
            }
        }.onSuccess { _messages.send("${month.year}년 ${month.monthValue}월 기록을 엑셀 파일로 저장했어요.") }
            .onFailure { _messages.send("저장하지 못했어요: ${it.message ?: it.javaClass.simpleName}") }
    }
}

private fun Rate.percentText(): String = ratio?.let { "${(it * 100).roundToInt()}%" } ?: "–"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val vm = appViewModel { DashboardViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var pickMonth by remember { mutableStateOf(false) }
    var exportMonth by rememberSaveable { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Xlsx.MIME)) { uri ->
        val month = exportMonth?.let { YearMonth.parse(it) }
        if (uri != null && month != null) vm.exportMonth(context.applicationContext, uri, month)
        exportMonth = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("대시보드", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = { pickMonth = true }) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("엑셀")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val nameWidth = nameColumnWidth(state.rows, available = maxWidth - 32.dp)
            LazyColumn(
                Modifier.fillMaxSize(),
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
                    item { WeekHeader(state, nameWidth) }
                    items(state.rows, key = { it.task.id }) { WeekRow(it, nameWidth) }
                } else {
                    item { MonthGrid(state) }
                    item { Text("할 일별 달성률", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    items(state.rows, key = { it.task.id }) { RateRow(it, state.days) }
                }
            }
        }
    }

    if (pickMonth) {
        MonthPickerDialog(
            initial = YearMonth.from(if (state.mode == DashMode.WEEK && state.to.isAfter(state.today)) state.today else state.from),
            latest = YearMonth.from(state.today),
            onDismiss = { pickMonth = false },
            onConfirm = { month ->
                pickMonth = false
                exportMonth = month.toString()
                exportLauncher.launch("repeatodo-$month.xlsx")
            },
        )
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

private val NameMinWidth = 104.dp
private val DayMinWidth = 44.dp

/**
 * Width of the task-name column in the weekly table: wide enough for the longest name when the
 * screen has room (tablets, unfolded foldables), but never squeezing the 7 day columns below
 * [DayMinWidth]. On phones this stays at [NameMinWidth] and long names show in a tooltip.
 */
@Composable
private fun nameColumnWidth(rows: List<DashRow>, available: Dp): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val titles = rows.map { it.task.title }
    val longest = remember(titles, style, density) {
        titles.maxOfOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width } ?: 0
    }
    val wanted = with(density) { longest.toDp() } + 12.dp
    val roomy = available - DayMinWidth * 7
    return wanted.coerceAtMost(roomy).coerceAtLeast(NameMinWidth)
}

@Composable
private fun WeekHeader(state: DashState, nameWidth: Dp) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(nameWidth))
        state.days.forEach { cell ->
            val isToday = cell.date == state.today
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val weekend = weekendColor(cell.date.dayOfWeek)
                Text(
                    dayLabel(cell.date.dayOfWeek),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) MaterialTheme.colorScheme.primary else weekend ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${cell.date.dayOfMonth}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.primary else weekend ?: MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun WeekRow(row: DashRow, nameWidth: Dp) {
    val color = Color(row.category?.color ?: NoCategoryColor)
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(nameWidth).padding(end = 4.dp)) {
            TaskName(row.task.title)
            val period = row.period
            if (period != null) {
                val text = (if (period.monthly) "이달 " else "") + "${period.doneDays}/${period.needDays}일" + when (period.state) {
                    SlotState.DONE -> " 완료"
                    SlotState.MISSED -> " 놓침"
                    else -> ""
                }
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    color = when (period.state) {
                        SlotState.DONE -> color
                        SlotState.MISSED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            } else if (row.multiCount) {
                Text(
                    "${row.amount}/${row.amountTarget}회",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        row.cells.forEachIndexed { i, state ->
            val count = if (row.multiCount) row.dayCounts.getOrNull(i) ?: 0 else 0
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SlotMark(state, color, count) }
        }
    }
}

/** A task name that shows the whole name in a tooltip when tapped (for names cut off by the narrow column). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskName(title: String) {
    val tooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(title) } },
        state = tooltip,
    ) {
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable(onClickLabel = "전체 이름 보기") { scope.launch { tooltip.show() } },
        )
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
    val week = weekOrder(state.weekStart)
    val lead = week.indexOf(state.from.dayOfWeek)
    val cells: List<DayCell?> = List(lead) { null } + state.days
    val primary = MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                week.forEach {
                    Text(
                        dayLabel(it),
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = weekendColor(it) ?: MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            cells.chunked(7).forEach { days ->
                Row(Modifier.fillMaxWidth()) {
                    for (i in 0 until 7) {
                        val cell = days.getOrNull(i)
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
                                        color = if (closed && ratio > 0.5f) MaterialTheme.colorScheme.onPrimary
                                        else weekendColor(cell.date.dayOfWeek) ?: MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (cell.anyTime.isNotEmpty()) {
                                        // "Any time" tasks worked on this day.
                                        Row(
                                            Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp),
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            cell.anyTime.take(4).forEach { c ->
                                                Box(Modifier.size(5.dp).background(Color(c), CircleShape).border(0.5.dp, Color.White, CircleShape))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "색이 진할수록 그날 할 일을 많이 끝낸 날이에요. 작은 점은 ‘아무 때나’ 할 일을 한 날이에요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RateRow(row: DashRow, days: List<DayCell>) {
    val color = Color(row.category?.color ?: NoCategoryColor)
    val detail = MaterialTheme.typography.labelSmall
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDot(row.category?.color ?: NoCategoryColor)
            Spacer(Modifier.width(8.dp))
            Text(row.task.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${row.rate.done}/${row.rate.counted}  ${row.rate.percentText()}", style = MaterialTheme.typography.bodySmall)
        }
        if (row.multiCount) {
            val each = if (row.period != null) "하는 날마다" else "하루"
            Text(
                "누적 ${row.amount}회 · 목표 ${row.amountTarget}회 ($each ${row.task.targetCount}회)",
                style = detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
        if (row.period != null) {
            // When an "any time" task was done this month.
            val worked = days.indices.filter { row.dayCounts[it] > 0 }
            val text = when {
                worked.isEmpty() -> "이달에 한 날이 아직 없어요"
                row.multiCount -> "한 날: " + worked.joinToString(", ") { "${days[it].date.dayOfMonth}일 ${row.dayCounts[it]}회" }
                else -> "한 날: " + worked.joinToString(", ") { "${days[it].date.dayOfMonth}" } + "일"
            }
            Text(text, style = detail, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp))
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { row.rate.ratio ?: 0f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = color,
        )
    }
}
