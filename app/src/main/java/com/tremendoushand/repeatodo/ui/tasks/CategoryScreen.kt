package com.tremendoushand.repeatodo.ui.tasks

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tremendoushand.repeatodo.AppContainer
import com.tremendoushand.repeatodo.data.CategoryEntity
import com.tremendoushand.repeatodo.ui.components.ColorDot
import com.tremendoushand.repeatodo.ui.components.appViewModel
import com.tremendoushand.repeatodo.ui.theme.CategoryPalette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CategoryViewModel(private val c: AppContainer) : ViewModel() {
    val categories = c.tasks.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(category: CategoryEntity?, name: String, color: Long) = viewModelScope.launch {
        val list = categories.value
        c.tasks.saveCategory(
            category?.copy(name = name, color = color)
                ?: CategoryEntity(id = "", name = name, color = color, sortOrder = (list.maxOfOrNull { it.sortOrder } ?: -1) + 1, updatedAt = 0),
        )
    }

    fun delete(category: CategoryEntity) = viewModelScope.launch { c.tasks.deleteCategory(category.id) }

    fun move(index: Int, delta: Int) = viewModelScope.launch {
        val list = categories.value.toMutableList()
        val target = index + delta
        if (target !in list.indices) return@launch
        list.add(target, list.removeAt(index))
        list.forEachIndexed { i, cat -> if (cat.sortOrder != i) c.tasks.saveCategory(cat.copy(sortOrder = i)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(onBack: () -> Unit) {
    val vm = appViewModel { CategoryViewModel(it) }
    val categories by vm.categories.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CategoryEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("카테고리") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "카테고리 추가") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            itemsIndexed(categories, key = { _, c -> c.id }) { index, cat ->
                ListItem(
                    modifier = Modifier.clickable { editing = cat },
                    leadingContent = { ColorDot(cat.color, size = 20.dp) },
                    headlineContent = { Text(cat.name) },
                    trailingContent = {
                        androidx.compose.foundation.layout.Row {
                            IconButton(onClick = { vm.move(index, -1) }, enabled = index > 0) { Icon(Icons.Filled.KeyboardArrowUp, "위로") }
                            IconButton(onClick = { vm.move(index, 1) }, enabled = index < categories.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "아래로") }
                        }
                    },
                )
            }
            item {
                Text(
                    "카테고리를 눌러 이름과 색상을 바꿀 수 있어요. 삭제해도 할 일은 남고 ‘카테고리 없음’이 돼요.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (adding || editing != null) {
        CategoryDialog(
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { name, color -> vm.save(editing, name, color); adding = false; editing = null },
            onDelete = editing?.let { cat -> { deleting = cat; editing = null } },
        )
    }
    deleting?.let { cat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("‘${cat.name}’ 카테고리를 삭제할까요?") },
            text = { Text("이 카테고리의 할 일은 삭제되지 않고 ‘카테고리 없음’으로 옮겨져요.") },
            confirmButton = { TextButton(onClick = { vm.delete(cat); deleting = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryDialog(
    initial: CategoryEntity?,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableLongStateOf(initial?.color ?: CategoryPalette.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "새 카테고리" else "카테고리 편집") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(20) }, label = { Text("이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryPalette.forEach { c ->
                        val selected = c == color
                        ColorDot(
                            c,
                            size = 32.dp,
                            modifier = Modifier
                                .let { if (selected) it.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else it }
                                .clickable { color = c },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), color) }, enabled = name.isNotBlank()) { Text("저장") } },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}
