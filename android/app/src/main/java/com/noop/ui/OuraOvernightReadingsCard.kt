package com.noop.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.noop.R
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Overnight samples alongside the existing daily trend. All readings remain available in a bounded page of rows. */
@Composable
internal fun OuraOvernightReadingsCard(vm: AppViewModel, owner: String, key: String, days: List<DailyMetric>, syncing: Boolean) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var day by remember(owner, key) { mutableStateOf(today) }
    var data by remember(owner, key, day) { mutableStateOf<OvernightVitalData?>(null) }
    var failed by remember(owner, key, day) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var expanded by remember(owner, key, day) { mutableStateOf(false) }
    var page by remember(owner, key, day) { mutableIntStateOf(0) }
    val enabled = key != "spo2" || NoopPrefs.spo2CandidateDisplay(context)
    LaunchedEffect(owner, key, day, days, syncing, enabled, retry) {
        if (!enabled) { data = null; return@LaunchedEffect }
        failed = false
        try {
            // History completion precedes the queued database writes; reread after they have had a turn.
            if (!syncing) delay(300)
            data = OuraOvernightReadings.load(vm.repo, owner, key, day, zone)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            data = null
            failed = true
        }
    }
    val tempUnit = UnitPrefs.temperature(context)
    val is24h = ClockPrefs.uses24Hour(context)
    val color = if (key == "spo2") Palette.metricCyan else Palette.metricRose
    val format: (Double) -> String = { value ->
        if (key == "spo2") "${value.toInt()}%"
        else UnitFormatter.temperatureFromCelsius(value, tempUnit, decimals = 2)
    }
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Overline(uiString(R.string.oura_overnight_title))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { day = day.minusDays(1) }) {
                    Icon(Icons.Filled.ChevronLeft, uiString(R.string.oura_overnight_previous), tint = Palette.accent)
                }
                TextButton(modifier = Modifier.weight(1f), onClick = {
                    DatePickerDialog(context, { _, y, m, d -> day = LocalDate.of(y, m + 1, d) },
                        day.year, day.monthValue - 1, day.dayOfMonth).apply {
                        datePicker.maxDate = System.currentTimeMillis()
                    }.show()
                }) {
                    Text(day.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)),
                        style = NoopType.subhead, color = Palette.textPrimary)
                }
                IconButton(enabled = day < today, onClick = { day = day.plusDays(1) }) {
                    Icon(Icons.Filled.ChevronRight, uiString(R.string.oura_overnight_next),
                        tint = if (day < today) Palette.accent else Palette.textTertiary)
                }
            }
            val loaded = data
            when {
                !enabled -> Text(uiString(R.string.oura_overnight_enable_spo2), style = NoopType.footnote, color = Palette.textSecondary)
                failed -> TextButton(onClick = { retry++ }) {
                    Text(uiString(R.string.oura_overnight_retry), color = Palette.accent)
                }
                loaded == null -> Text(uiString(R.string.l10n_health_screen_loading_33ce4174), style = NoopType.footnote, color = Palette.textSecondary)
                loaded.readings.isEmpty() -> Text(uiString(R.string.oura_overnight_empty), style = NoopType.footnote, color = Palette.textSecondary)
                else -> {
                    val readings = loaded.readings
                    val values = remember(readings) { readings.map { it.value } }
                    // Labels are built on demand (drawn points + the visible page), not for all ~20k samples.
                    val labelOf: (Int) -> String = { i ->
                        val r = readings[i]
                        val time = clockTimeLabel(r.ts, is24h)
                        if (r.count > 1) uiString(R.string.oura_overnight_sample_time, time, r.index + 1, r.count) else time
                    }
                    val segments = remember(readings) {
                        var segment = 0
                        readings.mapIndexed { index, reading ->
                            if (index > 0 && reading.ts - readings[index - 1].ts > 300) segment++
                            segment.toString()
                        }
                    }
                    // The chart draws at most CHART_MAX_POINTS (bucket min + max, so dips survive).
                    val drawn = remember(values) { OuraOvernightReadings.chartIndices(values) }
                    val chartValues = remember(drawn) { drawn.map { values[it] } }
                    val times = remember(drawn) { drawn.map { readings[it].ts } }
                    val chartLabels = remember(drawn, is24h) { drawn.map(labelOf) }
                    val chartSegments = remember(drawn) { drawn.map { segments[it] } }
                    Text(uiString(R.string.oura_overnight_count, readings.size), style = NoopType.subhead, color = Palette.textPrimary)
                    Text(uiString(R.string.oura_overnight_range, format(values.min()), format(values.max())),
                        style = NoopType.footnote, color = Palette.textSecondary)
                    LineChart(values = chartValues, modifier = Modifier.fillMaxWidth().height(Metrics.chartHeight),
                        color = color, fill = false, selectionEnabled = true, showsPoints = readings.size <= 500,
                        timestamps = times, selectionLabels = chartLabels, segmentIds = chartSegments, formatValue = format)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(clockTimeLabel(readings.first().ts, is24h), style = NoopType.footnote, color = Palette.textTertiary)
                        Text(clockTimeLabel(readings.last().ts, is24h), style = NoopType.footnote, color = Palette.textTertiary)
                    }
                    Text(uiString(if (key == "spo2") R.string.oura_overnight_spo2_note else R.string.oura_overnight_skin_note),
                        style = NoopType.footnote, color = Palette.textSecondary)
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(uiString(if (expanded) R.string.oura_overnight_hide else R.string.oura_overnight_show), color = Palette.accent)
                    }
                    if (expanded) {
                        val pages = (readings.size + 49) / 50
                        val current = page.coerceIn(0, pages - 1)
                        val start = current * 50
                        val end = minOf(start + 50, readings.size)
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(enabled = current > 0, onClick = { page = current - 1 }) {
                                Text(uiString(R.string.oura_overnight_previous_page))
                            }
                            Text(uiString(R.string.oura_overnight_page, start + 1, end, readings.size),
                                style = NoopType.footnote, color = Palette.textSecondary)
                            TextButton(enabled = current + 1 < pages, onClick = { page = current + 1 }) {
                                Text(uiString(R.string.oura_overnight_next_page))
                            }
                        }
                        for (index in start until end) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(labelOf(index), style = NoopType.footnote, color = Palette.textSecondary)
                                Text(format(readings[index].value), style = NoopType.bodyNumber, color = color)
                            }
                        }
                    }
                }
            }
        }
    }
}
