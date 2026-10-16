package com.tremendoushand.repeatodo.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.YearMonth

/** Picks the year and month to export, up to [latest]. */
@Composable
fun MonthPickerDialog(
    initial: YearMonth,
    latest: YearMonth,
    onDismiss: () -> Unit,
    onConfirm: (YearMonth) -> Unit,
) {
    var year by rememberSaveable { mutableIntStateOf(minOf(initial, latest).year) }
    var selected by rememberSaveable { mutableStateOf(minOf(initial, latest).toString()) }
    val picked = YearMonth.parse(selected)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("엑셀로 내보낼 달") },
        text = {
            Column {
                Text(
                    "고른 달의 기록이 달력 모양으로 저장돼요. 날짜마다 한 일과 횟수가 들어가요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { year-- }, enabled = year > 2000) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 해")
                    }
                    Text("${year}년", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { year++ }, enabled = year < latest.year) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 해")
                    }
                }
                (1..12).chunked(3).forEach { months ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        months.forEach { m ->
                            val ym = YearMonth.of(year, m)
                            val enabled = !ym.isAfter(latest)
                            val modifier = Modifier.weight(1f)
                            if (ym == picked) {
                                FilledTonalButton(onClick = {}, modifier = modifier) { Text("${m}월") }
                            } else {
                                TextButton(onClick = { selected = ym.toString() }, enabled = enabled, modifier = modifier) { Text("${m}월") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) { Text("${picked.year}년 ${picked.monthValue}월 저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
