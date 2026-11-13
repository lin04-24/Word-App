package com.example.moci

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.moci.data.*
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun StatisticsScreen(state: LearningUiState, goal: Int) {
    var range by rememberSaveable { mutableIntStateOf(30) }
    val days = remember(state.daily, state.today, range) { LearningStatistics.days(state.daily, state.today, range) }
    val mastery = remember(state.snapshot.words) { LearningStatistics.mastery(state.snapshot.words) }
    LazyColumn(Modifier.fillMaxSize().testTag("statisticsList"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("学习概况", style = MaterialTheme.typography.titleLarge)
            Text("总词数 ${state.snapshot.words.size} · 待补释义 ${mastery.missing}")
            Text("待复习 ${state.dueCount} · 当前错词 ${state.wrongKeys.size}")
            Text("今日目标 ${state.daily[state.today]?.wordCount ?: 0} / $goal 个")
        }
        item { MasteryChart(mastery) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = range == 7, onClick = { range = 7 }, label = { Text("最近 7 天") })
                FilterChip(selected = range == 30, onClick = { range = 30 }, label = { Text("最近 30 天") })
            }
            Text("${days.first().date} 至 ${days.last().date}", style = MaterialTheme.typography.bodySmall)
        }
        item { LearningBarChart(days) }
        item { AccuracyChart(days) }
        item { LearningCalendar(state) }
        item {
            Text("每日明细", style = MaterialTheme.typography.titleLarge)
            Text("正确率按所有作答计算，包含错词重练。", style = MaterialTheme.typography.bodySmall)
        }
        items(days.asReversed(), key = { it.date.toString() }) { day ->
            Text("${day.date}：${day.wordCount} 词 · ${day.attempts} 次作答 · 正确率 ${formatAccuracy(day.accuracy)}")
        }
    }
}

@Composable
private fun MasteryChart(data: MasteryDistribution) {
    val values = listOf(data.missing, data.unlearned, data.learning, data.mastered)
    val labels = listOf("待补释义", "未学习", "巩固中", "已掌握")
    val colors = listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.outline,
        MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.primary)
    val total = values.sum()
    val description = labels.zip(values).joinToString("，") { "${it.first} ${it.second} 个" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("掌握情况", style = MaterialTheme.typography.titleMedium)
            Box(Modifier.align(Alignment.CenterHorizontally).size(180.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().testTag("masteryChart").semantics { contentDescription = description }) {
                    val stroke = 22.dp.toPx()
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    var angle = -90f
                    if (total == 0) drawArc(colors[1].copy(alpha = .25f), 0f, 360f, false,
                        topLeft = Offset(stroke / 2, stroke / 2), size = arcSize, style = Stroke(stroke))
                    else values.forEachIndexed { index, value ->
                        val sweep = value.toFloat() / total * 360f
                        drawArc(colors[index], angle, sweep, false, topLeft = Offset(stroke / 2, stroke / 2), size = arcSize, style = Stroke(stroke))
                        angle += sweep
                    }
                }
                Text("$total 个", style = MaterialTheme.typography.headlineSmall)
            }
            labels.forEachIndexed { index, label -> Text("$label：${values[index]} 个", color = colors[index]) }
            Text("等级达到 6 且已有练习记录，计为已掌握。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LearningBarChart(days: List<DayStatistics>) {
    val color = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outlineVariant
    val max = (days.maxOfOrNull { it.wordCount } ?: 0).coerceAtLeast(1)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("每日学习量", style = MaterialTheme.typography.titleMedium)
            Text("纵轴：不同单词数（0–$max） · 横轴：日期", style = MaterialTheme.typography.bodySmall)
            Canvas(Modifier.fillMaxWidth().height(150.dp).testTag("learningBarChart").semantics {
                contentDescription = "每日学习量柱状图。" + days.joinToString("；") { "${it.date} ${it.wordCount} 词" }
            }) {
                val width = size.width / days.size
                drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height))
                days.forEachIndexed { i, day ->
                    val height = day.wordCount.toFloat() / max * size.height
                    drawRect(color, Offset(i * width + width * .18f, size.height - height), Size(width * .64f, height))
                }
            }
            ChartDates(days)
            Text("同一个词在同一天练习多次，只计 1 个。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AccuracyChart(days: List<DayStatistics>) {
    val color = MaterialTheme.colorScheme.tertiary
    val axis = MaterialTheme.colorScheme.outlineVariant
    val total = days.sumOf { it.attempts }
    val accuracy = if (total == 0) null else days.sumOf { it.correct }.toDouble() / total
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("正确率趋势", style = MaterialTheme.typography.titleMedium)
            Text("本期正确率 ${formatAccuracy(accuracy)} · 纵轴 0–100%", style = MaterialTheme.typography.bodySmall)
            Canvas(Modifier.fillMaxWidth().height(150.dp).testTag("accuracyChart").semantics {
                contentDescription = "每日正确率折线图。" + days.joinToString("；") { "${it.date} ${formatAccuracy(it.accuracy)}" }
            }) {
                val inset = 5.dp.toPx()
                val plotHeight = size.height - 2 * inset
                drawLine(axis, Offset(inset, inset), Offset(size.width - inset, inset))
                drawLine(axis, Offset(inset, size.height - inset), Offset(size.width - inset, size.height - inset))
                var previous: Offset? = null
                days.forEachIndexed { index, day ->
                    val rate = day.accuracy
                    if (rate == null) previous = null else {
                        val point = Offset(inset + index.toFloat() / (days.size - 1).coerceAtLeast(1) * (size.width - 2 * inset),
                            inset + (1f - rate.toFloat()) * plotHeight)
                        previous?.let { drawLine(color, it, point, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round) }
                        drawCircle(color, 3.dp.toPx(), point)
                        previous = point
                    }
                }
            }
            ChartDates(days)
            Text("无作答日期显示为“—”，折线在该日期断开。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ChartDates(days: List<DayStatistics>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(days.first().date.toString(), style = MaterialTheme.typography.labelSmall)
        Text(days.last().date.toString(), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LearningCalendar(state: LearningUiState) {
    var monthText by rememberSaveable(state.today) { mutableStateOf(YearMonth.from(state.today).toString()) }
    var selectedText by rememberSaveable(state.today) { mutableStateOf(state.today.toString()) }
    val month = YearMonth.parse(monthText)
    val selected = LocalDate.parse(selectedText)
    val offset = month.atDay(1).dayOfWeek.value - 1
    val cells = ((offset + month.lengthOfMonth() + 6) / 7) * 7
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("学习日历", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { monthText = month.minusMonths(1).toString(); selectedText = month.minusMonths(1).atDay(1).toString() }) { Text("上月") }
                Text(month.toString(), fontWeight = FontWeight.Bold)
                TextButton(onClick = { monthText = month.plusMonths(1).toString(); selectedText = month.plusMonths(1).atDay(1).toString() }, enabled = month < YearMonth.from(state.today)) { Text("下月") }
            }
            Row { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
            repeat(cells / 7) { row ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { column ->
                        val number = row * 7 + column - offset + 1
                        if (number !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(48.dp)) else {
                            val date = month.atDay(number)
                            val learned = (state.daily[date]?.attempts ?: 0) > 0
                            Surface(modifier = Modifier.weight(1f).height(48.dp)
                                .semantics { contentDescription = "$date ${if (learned) "有学习记录" else "无学习记录"}" }
                                .clickable { selectedText = date.toString() },
                                color = if (selected == date) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.small) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(number.toString())
                                    Text(if (learned) "●" else " ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
            val day = state.daily[selected] ?: DayStatistics(selected, 0, 0, 0)
            Text("$selected：${day.wordCount} 词 · ${day.attempts} 次作答 · 正确率 ${formatAccuracy(day.accuracy)}",
                modifier = Modifier.testTag("calendarDaySummary"))
        }
    }
}
