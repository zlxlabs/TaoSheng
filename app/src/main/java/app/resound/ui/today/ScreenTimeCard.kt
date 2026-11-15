package app.resound.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.resound.AppGraph
import app.resound.screentime.ScreenTimeReport
import app.resound.screentime.ScreenTimeReports
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toKotlinLocalDate

@Composable
internal fun ScreenTimeCard(date: LocalDate) {
    val c = Rs.colors
    var report by remember(date) { mutableStateOf<ScreenTimeReport?>(null) }
    var open by remember(date) { mutableStateOf(false) }
    var resumeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(date, resumeTick) {
        report = withContext(Dispatchers.IO) { loadScreenTimeReport(date) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val shown = report ?: return
    RsCard(Modifier.springClick { open = true }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("屏幕时间", style = RsType.headline, color = c.text, modifier = Modifier.weight(1f))
            Text(screenTimeDurationText(shown.totalMinutes), style = RsType.headline, color = c.text)
        }
        val empty = shown.result.sessions.isEmpty() && shown.result.gaps.isEmpty()
        if (empty) {
            Spacer(Modifier.height(8.dp))
            Text("今天还没有记录", style = RsType.subhead, color = c.textSecondary)
        } else {
            val top = shown.apps.take(3)
            val maxMinutes = top.firstOrNull()?.minutes ?: 0
            if (top.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    top.forEach { app -> ScreenTimeAppRow(app.name, app.minutes, maxMinutes) }
                }
            }
        }
        if (shown.result.devicePlayback.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("设备播放记录 ${shown.result.devicePlayback.size} 段", style = RsType.caption, color = c.textSecondary)
        }
        val gapFix = shown.result.gaps.firstOrNull()?.fix
        if (gapFix != null) {
            Spacer(Modifier.height(4.dp))
            Text(gapFix, style = RsType.caption, color = c.warning)
        }
    }
    if (open) ScreenTimeSheet(date, shown, onDismiss = { open = false })
}

internal suspend fun loadScreenTimeReport(date: LocalDate): ScreenTimeReport? {
    val reader = AppGraph.screenTimeReader ?: return null
    val zone = ZoneId.systemDefault()
    val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val until = minOf(dayEnd, System.currentTimeMillis())
    if (until <= from) return null
    return ScreenTimeReports.build(
        date.toKotlinLocalDate(),
        TimeZone.of(zone.id),
        reader.sessions(from, until),
    )
}

internal fun screenTimeDurationText(minutes: Int): String =
    if (minutes < 60) "$minutes 分" else "${minutes / 60} 小时 ${minutes % 60} 分"

internal fun screenTimeMinutes(ms: Long): Int = ((ms.coerceAtLeast(0L) + 30_000L) / 60_000L).toInt()

internal fun screenTimeClockRange(from: Long, until: Long): String {
    val end = if (until == Long.MAX_VALUE) "至今" else formatClock(until)
    return "${formatClock(from)}–$end"
}

@Composable
internal fun ScreenTimeAppRow(name: String, minutes: Int, maxMinutes: Int) {
    val c = Rs.colors
    val fraction = if (maxMinutes <= 0) 0f else (minutes.toFloat() / maxMinutes).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = RsType.subhead,
                color = c.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("$minutes 分", style = RsType.subhead, color = c.textSecondary)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            Box(
                Modifier.fillMaxWidth(fraction).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(c.accent),
            )
        }
    }
}
