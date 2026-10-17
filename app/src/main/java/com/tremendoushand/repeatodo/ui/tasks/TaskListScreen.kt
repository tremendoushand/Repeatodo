package com.tremendoushand.repeatodo.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tremendoushand.repeatodo.AppContainer
import com.tremendoushand.repeatodo.data.CategoryEntity
import com.tremendoushand.repeatodo.data.TaskEntity
import com.tremendoushand.repeatodo.ui.components.ColorDot
import com.tremendoushand.repeatodo.ui.components.appViewModel
import com.tremendoushand.repeatodo.ui.components.repeatLabel
import com.tremendoushand.repeatodo.ui.theme.NoCategoryColor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek

data class TaskListState(
    val tasks: List<TaskEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
)

class TaskListViewModel(c: AppContainer) : ViewModel() {
    val state = combine(c.tasks.tasks, c.tasks.categories, c.settings.settings) { t, cats, s -> TaskListState(t, cats, s.weekStart) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskListState())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(onAdd: (categoryId: String?) -> Unit, onOpen: (String) -> Unit, onCategories: () -> Unit) {
    val vm = appViewModel { TaskListViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var showFinished by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("할 일", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onCategories) { Icon(Icons.Outlined.Category, contentDescription = "카테고리 관리") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAdd(filter) }) { Icon(Icons.Filled.Add, contentDescription = "할 일 추가") }
        },
    ) { padding ->
        val byId = state.categories.associateBy { it.id }
        val visible = state.tasks.filter { filter == null || it.categoryId == filter }
        val active = visible.filter { !it.archived }
        val finished = visible.filter { it.archived }
        // Grouped in category order, uncategorized last.
        val groups = active.groupBy { it.categoryId?.let(byId::get) }
            .toList()
            .sortedBy { it.first?.sortOrder ?: Int.MAX_VALUE }

        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("전체") })
                    state.categories.forEach { cat ->
                        FilterChip(
                            selected = filter == cat.id,
                            onClick = { filter = if (filter == cat.id) null else cat.id },
                            label = { Text(cat.name) },
                            leadingIcon = { ColorDot(cat.color) },
                        )
                    }
                }
            }
            if (active.isEmpty()) {
                item {
                    Text(
                        "할 일이 없어요. + 버튼으로 추가해 보세요.",
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            groups.forEach { (category, tasks) ->
                item(key = "cat-${category?.id}") {
                    CategoryGroup(
                        name = category?.name ?: "카테고리 없음",
                        color = Color(category?.color ?: NoCategoryColor),
                        tasks = tasks,
                        weekStart = state.weekStart,
                        onOpen = onOpen,
                    )
                }
            }
            if (finished.isNotEmpty()) {
                item {
                    TextButton(onClick = { showFinished = !showFinished }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                        Text(if (showFinished) "끝난 할 일 숨기기" else "끝난 할 일 ${finished.size}개 보기")
                    }
                }
                if (showFinished) {
                    item(key = "finished") {
                        CategoryGroup(
                            name = "끝난 할 일",
                            color = MaterialTheme.colorScheme.outline,
                            tasks = finished,
                            weekStart = state.weekStart,
                            onOpen = onOpen,
                            colorOf = { task -> task.categoryId?.let(byId::get)?.color?.let { Color(it) } },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One category as a card: a tinted header with the category's name, and its tasks indented
 * underneath with a color rail, so the two levels read apart.
 */
@Composable
private fun CategoryGroup(
    name: String,
    color: Color,
    tasks: List<TaskEntity>,
    weekStart: DayOfWeek,
    onOpen: (String) -> Unit,
    colorOf: (TaskEntity) -> Color? = { null },
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().background(color.copy(alpha = 0.16f)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(14.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${tasks.size}개", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        tasks.forEachIndexed { i, task ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 36.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            TaskItem(task, colorOf(task) ?: color, weekStart) { onOpen(task.id) }
        }
    }
}

@Composable
private fun TaskItem(task: TaskEntity, color: Color, weekStart: DayOfWeek, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 22.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(36.dp).background(color.copy(alpha = 0.7f), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge)
            Text(repeatLabel(task, weekStart), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.note.isNotBlank()) {
                Text(
                    task.note,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
