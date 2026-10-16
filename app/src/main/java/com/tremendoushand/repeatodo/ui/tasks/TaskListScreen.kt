package com.tremendoushand.repeatodo.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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

data class TaskListState(val tasks: List<TaskEntity> = emptyList(), val categories: List<CategoryEntity> = emptyList())

class TaskListViewModel(c: AppContainer) : ViewModel() {
    val state = combine(c.tasks.tasks, c.tasks.categories) { t, cats -> TaskListState(t, cats) }
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
                    Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) {
                        ColorDot(category?.color ?: NoCategoryColor, Modifier.padding(top = 4.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(category?.name ?: "카테고리 없음", style = MaterialTheme.typography.titleSmall)
                    }
                }
                items(tasks, key = { it.id }) { task -> TaskItem(task) { onOpen(task.id) } }
            }
            if (finished.isNotEmpty()) {
                item {
                    HorizontalDivider(Modifier.padding(top = 16.dp))
                    TextButton(onClick = { showFinished = !showFinished }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(if (showFinished) "끝난 할 일 숨기기" else "끝난 할 일 ${finished.size}개 보기")
                    }
                }
                if (showFinished) items(finished, key = { "f-" + it.id }) { task -> TaskItem(task) { onOpen(task.id) } }
            }
        }
    }
}

@Composable
private fun TaskItem(task: TaskEntity, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        headlineContent = { Text(task.title) },
        supportingContent = {
            Column {
                Text(repeatLabel(task))
                if (task.note.isNotBlank()) Text(task.note, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
