package com.tremendoushand.repeatodo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tremendoushand.repeatodo.AppContainer
import com.tremendoushand.repeatodo.RepeatodoApp
import com.tremendoushand.repeatodo.data.TaskEntity
import com.tremendoushand.repeatodo.domain.LAST_DAY
import com.tremendoushand.repeatodo.domain.RepeatType
import com.tremendoushand.repeatodo.domain.WeekDays
import java.time.DayOfWeek

/** A ViewModel built from the app's [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as RepeatodoApp).container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

@Composable
fun ColorDot(color: Long, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    Box(modifier.size(size).background(Color(color), CircleShape))
}

fun dayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "월"
    DayOfWeek.TUESDAY -> "화"
    DayOfWeek.WEDNESDAY -> "수"
    DayOfWeek.THURSDAY -> "목"
    DayOfWeek.FRIDAY -> "금"
    DayOfWeek.SATURDAY -> "토"
    DayOfWeek.SUNDAY -> "일"
}

fun formatTime(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    val ampm = if (h < 12) "오전" else "오후"
    val h12 = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "$ampm $h12:${m.toString().padStart(2, '0')}"
}

fun repeatLabel(task: TaskEntity): String {
    val days = task.goal.daysPerPeriod
    val base = when (task.repeatType) {
        RepeatType.DAILY -> "매일"
        RepeatType.WEEKLY ->
            if (task.weekDays and WeekDays.ALL == 0) "주중 아무 때나" + if (days > 1) " ${days}일" else ""
            else if (task.weekDays and WeekDays.ALL == WeekDays.ALL) "매주 매일"
            else "매주 " + WeekDays.toList(task.weekDays).joinToString("·") { dayLabel(it) }
        RepeatType.MONTHLY -> when {
            task.monthDay == 0 -> "월중 아무 때나" + if (days > 1) " ${days}일" else ""
            task.monthDay >= LAST_DAY -> "매월 말일"
            else -> "매월 ${task.monthDay}일"
        }
    }
    val parts = mutableListOf(base)
    if (task.targetCount > 1) parts += if (task.rule.isFlexible) "하는 날마다 ${task.targetCount}회" else "${task.targetCount}회씩"
    task.reminderMinutes?.let { parts += formatTime(it) }
    task.totalCount?.let { parts += "총 ${it}번" }
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initialMinutes: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = initialMinutes / 60, initialMinute = initialMinutes % 60, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        text = { TimePicker(state = state) },
    )
}
