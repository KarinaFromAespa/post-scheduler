package com.postscheduler.ui.publish

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.postscheduler.data.local.Post
import com.postscheduler.data.local.PostStatus
import com.postscheduler.ui.components.KeepTimeInFuture
import com.postscheduler.ui.components.PostCard
import com.postscheduler.ui.components.dayLabel
import com.postscheduler.ui.components.todayOrLaterDates
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val dayKeyFmt = SimpleDateFormat("yyyy-DDD", Locale.getDefault())
private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
private val shortDateFmt = SimpleDateFormat("MMM d", Locale.getDefault())

/** Publish tab filter: everything, one day, or a date range. */
sealed interface PublishFilter {
    data object All : PublishFilter
    data class Day(val dayStartMillis: Long) : PublishFilter
    data class Range(val startDayMillis: Long, val endDayMillis: Long) : PublishFilter
}

private enum class FilterDateStep { SINGLE, RANGE_START, RANGE_END }

private fun startOfDay(millis: Long): Long {
    return Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun endOfDay(millis: Long): Long {
    return Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.timeInMillis
}

private fun shortDate(millis: Long): String = shortDateFmt.format(Date(millis))

/**
 * Publish tab = home. Timeline grouped by day: each day gets
 * a header (Today, Tomorrow, or the date), then its cards, each showing
 * its exact scheduled time. Only days that actually have a scheduled post
 * are shown. Multiple items can share a day — each keeps its own
 * status/failure. Drafts are shown in a separate section below the queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishScreen(
    onCreate: () -> Unit,
    onEdit: (com.postscheduler.data.local.Post) -> Unit = {},
    vm: PublishViewModel = viewModel()
) {
    val queue by vm.queue.collectAsState(initial = emptyList())
    val paused by vm.paused.collectAsState()
    val error by vm.error.collectAsState()
    val isRefreshing by vm.refreshing.collectAsState()

    // "Update schedule" state: the post being moved, and the date/time
    // picker flow (date first, then time — same as the composer).
    var rescheduleTarget by remember { mutableStateOf<Post?>(null) }
    var showRescheduleDate by remember { mutableStateOf(false) }
    var showRescheduleTime by remember { mutableStateOf(false) }
    var reschedulePickedDate by remember { mutableStateOf<Long?>(null) }
    var rescheduleError by remember { mutableStateOf<String?>(null) }

    // Publish filter: All (every scheduled post) is the default; a day
    // shows only that day, a range shows every day from start to end.
    var filter by remember { mutableStateOf<PublishFilter>(PublishFilter.All) }
    var filterDateStep by remember { mutableStateOf<FilterDateStep?>(null) }
    var rangeStartPicked by remember { mutableStateOf<Long?>(null) }

    // Pull the latest queue every time this tab is shown, so posts scheduled
    // elsewhere (website, or after this screen was first opened) appear.
    // Silent — no pull indicator. Showing the spinner on every tab switch
    // felt like the tab was force-refreshing.
    LaunchedEffect(Unit) { vm.refresh(showIndicator = false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Today", style = MaterialTheme.typography.headlineMedium)
                Text(
                    dateFmt.format(Date()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { vm.togglePause() }) {
                Icon(
                    if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = null, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(4.dp))
                Text(if (paused) "Resume queue" else "Pause queue")
            }
        }
        if (paused) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Pause, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Queue paused — nothing will publish until you resume it.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        error?.let {
            Text(
                "Backend unreachable: $it — showing the local queue.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        rescheduleError?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // Filter chips, under the Today/date header: All shows every
        // scheduled post; a day shows only that day; a range shows every
        // day from the start date to the end date. Tapping the active
        // Date/Range chip re-opens the picker to change it.
        val dateChipLabel = when (val f = filter) {
            is PublishFilter.Day -> shortDate(f.dayStartMillis)
            else -> "Pick a date"
        }
        val rangeChipLabel = when (val f = filter) {
            is PublishFilter.Range -> if (f.startDayMillis == f.endDayMillis) shortDate(f.startDayMillis)
                else "${shortDate(f.startDayMillis)} – ${shortDate(f.endDayMillis)}"
            else -> "Pick a range"
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filter == PublishFilter.All,
                onClick = { filter = PublishFilter.All },
                label = { Text("All") }
            )
            FilterChip(
                selected = filter is PublishFilter.Day,
                onClick = { filterDateStep = FilterDateStep.SINGLE },
                label = { Text(dateChipLabel) }
            )
            FilterChip(
                selected = filter is PublishFilter.Range,
                onClick = { filterDateStep = FilterDateStep.RANGE_START },
                label = { Text(rangeChipLabel) }
            )
        }

        // Group by day, not by hour. The old hourly slots showed the
        // slot's start time as the header (e.g. "21:00") while a post
        // inside it was really at 21:58 — the header read as the scheduled
        // time and was wrong. The exact time lives on each card now.
        // Grouping by day also makes posts scheduled for tomorrow (or
        // later) visible: the old slots only covered today's 24 hours, so
        // a tomorrow post matched no slot and never appeared.
        val filteredQueue = when (val f = filter) {
            PublishFilter.All -> queue
            is PublishFilter.Day -> queue.filter {
                it.scheduledAtMillis?.let { t ->
                    dayKeyFmt.format(Date(t)) == dayKeyFmt.format(Date(f.dayStartMillis))
                } == true
            }
            is PublishFilter.Range -> queue.filter {
                it.scheduledAtMillis?.let { t -> t in f.startDayMillis..endOfDay(f.endDayMillis) } == true
            }
        }
        val dayGroups = filteredQueue
            .filter { it.scheduledAtMillis != null }
            .groupBy { dayKeyFmt.format(Date(it.scheduledAtMillis!!)) }
            .map { (_, posts) ->
                dayLabel(posts.first().scheduledAtMillis!!) to posts.sortedBy { it.scheduledAtMillis }
            }
            .sortedBy { it.second.first().scheduledAtMillis }

        // Failed posts, from the whole queue so the banner alerts even
        // when a date filter hides them. Tapping it shows everything.
        val failed = queue.filter { it.status == PostStatus.FAILED }
        // A Publish Now that failed has no scheduled time, so it has no
        // day to group under — it gets its own group at the top.
        val failedNoTime = filteredQueue.filter { it.status == PostStatus.FAILED && it.scheduledAtMillis == null }
        val allGroups = if (failedNoTime.isNotEmpty()) listOf("Failed" to failedNoTime) + dayGroups else dayGroups

        // Pull down to re-pull the queue from the backend, so posts
        // scheduled on the website (or changed elsewhere) show up
        // without leaving the tab.
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.weight(1f)
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            if (failed.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { filter = PublishFilter.All }
                            .background(
                                MaterialTheme.colorScheme.errorContainer,
                                androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                            ).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (failed.size == 1) "1 post failed to publish"
                            else "${failed.size} posts failed to publish",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "Tap to see",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            if (allGroups.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CalendarMonth, contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (filter == PublishFilter.All) "Nothing else is queued up"
                            else "No posts for this filter",
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (filter == PublishFilter.All) "Tap + Schedule a post to line up the day."
                            else "Try a different date or range, or show everything.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        if (filter == PublishFilter.All) {
                            Button(onClick = onCreate) { Text("+ Schedule a post") }
                        } else {
                            Button(onClick = { filter = PublishFilter.All }) { Text("Show all") }
                        }
                    }
                }
            }
            items(allGroups) { (day, atDay) ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(10.dp).background(
                                MaterialTheme.colorScheme.primary, CircleShape
                            )
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            day,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (atDay.size > 1) {
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "${atDay.size} posts",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    atDay.forEach { post ->
                        PostCard(
                            post = post,
                            // "Publish now" is the intentional way to send a
                            // scheduled post early. "Retry" is only for a
                            // failed post — showing it on a scheduled post
                            // let a tap publish it ahead of time by mistake.
                            onPublishNow = { vm.publishNow(post) },
                            onDelete = { vm.delete(post) },
                            onRetry = if (post.status == PostStatus.FAILED) ({ vm.publishNow(post) }) else null,
                            onEdit = if (post.status == PostStatus.SCHEDULED || post.status == PostStatus.FAILED) ({ onEdit(post) }) else null,
                            // "Update schedule" moves just the time, without
                            // opening the full Edit flow.
                            onUpdateSchedule = if (post.status == PostStatus.SCHEDULED) ({
                                rescheduleTarget = post
                                rescheduleError = null
                                showRescheduleDate = true
                            }) else null
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
        }

        // "Update schedule" picker: date first, then time. The pickers open
        // on the post's current scheduled time, so the user can nudge it.
        // key(target.id) recreates the picker states when a different post
        // is picked, so a second reschedule doesn't show the first post's time.
        rescheduleTarget?.let { target ->
            key(target.id) {
                val targetCal = Calendar.getInstance().apply {
                    timeInMillis = target.scheduledAtMillis ?: System.currentTimeMillis()
                }
                val dateState = rememberDatePickerState(
                    initialSelectedDateMillis = target.scheduledAtMillis ?: System.currentTimeMillis(),
                    selectableDates = todayOrLaterDates
                )
                val timeState = rememberTimePickerState(
                    initialHour = targetCal.get(Calendar.HOUR_OF_DAY),
                    initialMinute = targetCal.get(Calendar.MINUTE),
                    is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
                )
                // When the picked day is today, a past time cannot be
                // picked — the picker is moved to the earliest schedulable
                // time (now + 2 min), so AM is effectively locked once it
                // is PM.
                KeepTimeInFuture(
                    timeState,
                    reschedulePickedDate ?: target.scheduledAtMillis,
                    showRescheduleTime
                )
                if (showRescheduleDate) {
                    DatePickerDialog(
                        onDismissRequest = { showRescheduleDate = false; rescheduleTarget = null },
                        confirmButton = {
                            TextButton(onClick = {
                                reschedulePickedDate = dateState.selectedDateMillis
                                showRescheduleDate = false
                                showRescheduleTime = true
                            }) { Text("Next") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRescheduleDate = false; rescheduleTarget = null }) { Text("Cancel") }
                        }
                    ) { DatePicker(state = dateState) }
                }
                if (showRescheduleTime) {
                    AlertDialog(
                        onDismissRequest = { showRescheduleTime = false; rescheduleTarget = null },
                        confirmButton = {
                            TextButton(onClick = {
                                val day = reschedulePickedDate ?: target.scheduledAtMillis ?: System.currentTimeMillis()
                                val cal = Calendar.getInstance().apply {
                                    timeInMillis = day
                                    set(Calendar.HOUR_OF_DAY, timeState.hour)
                                    set(Calendar.MINUTE, timeState.minute)
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }
                                val picked = cal.timeInMillis
                                if (picked < System.currentTimeMillis() + 2 * 60 * 1000) {
                                    rescheduleError = "Pick a time at least 2 minutes ahead."
                                } else {
                                    vm.updateSchedule(target, picked)
                                    rescheduleError = null
                                }
                                showRescheduleTime = false
                                rescheduleTarget = null
                            }) { Text("Set time") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRescheduleTime = false; rescheduleTarget = null }) { Text("Cancel") }
                        },
                        text = { TimePicker(state = timeState) }
                    )
                }
            }
        }

        // Filter date picker: one date for the Day filter, two in a row
        // (start, then end) for the Range filter. key(step) recreates the
        // picker state when the step changes, so the end picker opens on
        // the picked start date instead of reusing the start picker's state.
        // A range picked backwards is ordered, not rejected.
        filterDateStep?.let { step ->
            key(step) {
                val initial = when (step) {
                    FilterDateStep.SINGLE -> (filter as? PublishFilter.Day)?.dayStartMillis ?: System.currentTimeMillis()
                    FilterDateStep.RANGE_START -> (filter as? PublishFilter.Range)?.startDayMillis ?: System.currentTimeMillis()
                    FilterDateStep.RANGE_END -> rangeStartPicked ?: System.currentTimeMillis()
                }
                val filterDateState = rememberDatePickerState(
                    initialSelectedDateMillis = initial,
                    selectableDates = todayOrLaterDates
                )
                DatePickerDialog(
                    onDismissRequest = { filterDateStep = null; rangeStartPicked = null },
                    confirmButton = {
                        TextButton(onClick = {
                            val picked = filterDateState.selectedDateMillis ?: System.currentTimeMillis()
                            when (step) {
                                FilterDateStep.SINGLE -> {
                                    filter = PublishFilter.Day(startOfDay(picked))
                                    filterDateStep = null
                                }
                                FilterDateStep.RANGE_START -> {
                                    rangeStartPicked = startOfDay(picked)
                                    filterDateStep = FilterDateStep.RANGE_END
                                }
                                FilterDateStep.RANGE_END -> {
                                    val a = rangeStartPicked ?: startOfDay(picked)
                                    val b = startOfDay(picked)
                                    filter = PublishFilter.Range(a.coerceAtMost(b), a.coerceAtLeast(b))
                                    filterDateStep = null
                                    rangeStartPicked = null
                                }
                            }
                        }) { Text(if (step == FilterDateStep.RANGE_START) "Next" else "Apply") }
                    },
                    dismissButton = {
                        TextButton(onClick = { filterDateStep = null; rangeStartPicked = null }) { Text("Cancel") }
                    }
                ) { DatePicker(state = filterDateState) }
            }
        }
    }
}
