package com.postscheduler.ui.create

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.postscheduler.data.local.Post
import com.postscheduler.data.local.PostType
import com.postscheduler.ui.components.KeepTimeInFuture
import com.postscheduler.ui.components.MediaRow
import com.postscheduler.ui.components.PageAvatar
import com.postscheduler.ui.components.todayOrLaterDates
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Composer: feed post / reel (single MP4) / story (one photo or video).
 * Post and reel take a caption (hashtags go in the caption); a story
 * has neither — it is just the photo or video. Crop is done by the system photo
 * picker / editor before the URI reaches here — v1 does not ship its own
 * cropper. Schedule = a time today (hourly slots); Publish Now bypasses
 * the queue.
 * Saving with no time = Draft.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(
    onDone: () -> Unit,
    onConnectPage: () -> Unit,
    editPost: Post? = null,
    vm: CreateViewModel = viewModel()
) {
    val type by vm.type.collectAsState()
    val saving by vm.saving.collectAsState()
    val error by vm.error.collectAsState()
    val done by vm.done.collectAsState()
    val pageName by vm.pageName.collectAsState()
    val pagePictureUrl by vm.pagePictureUrl.collectAsState()
    val pages by vm.pages.collectAsState()
    val selectedPageId by vm.selectedPageId.collectAsState()
    val pickedMedia by vm.media.collectAsState()
    val hasPendingUploads by vm.hasPendingUploads.collectAsState()
    var pageMenuExpanded by remember { mutableStateOf(false) }

    var caption by remember { mutableStateOf(editPost?.caption ?: "") }
    var schedule by remember { mutableStateOf<Long?>(editPost?.scheduledAtMillis ?: vm.defaultScheduleMillis()) }
    // When true the composer saves as a draft (keeping any picked time as
    // the draft's planned time) instead of scheduling it.
    var saveAsDraft by remember { mutableStateOf(false) }
    var scheduleError by remember { mutableStateOf<String?>(null) }

    // Editing a scheduled post: pre-fill the form and tell the ViewModel
    // to PATCH this post on save instead of creating a new one.
    LaunchedEffect(editPost?.id) {
        if (editPost != null) {
            vm.loadForEdit(editPost)
            caption = editPost.caption
            // A past planned/scheduled time must be changed before it can
            // be scheduled again — prompt for a new time.
            val t = editPost.scheduledAtMillis
            if (t != null && t < System.currentTimeMillis() + 2 * 60 * 1000) {
                schedule = null
                scheduleError = "This post's planned time has passed — pick a new time."
            } else {
                schedule = t
            }
        }
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pickedDateMillis by remember { mutableStateOf<Long?>(null) }
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = schedule ?: System.currentTimeMillis(),
        selectableDates = todayOrLaterDates
    )
    val context = LocalContext.current
    val timeState = rememberTimePickerState(
        initialHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        initialMinute = 0,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    )
    // When the picked day is today, a past time cannot be picked — the
    // picker is moved to the earliest schedulable time (now + 2 min), so
    // AM is effectively locked once it is PM.
    KeepTimeInFuture(timeState, pickedDateMillis, showTimePicker)

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        vm.addMedia(uris)
    }

    // Stories and reels take exactly one file, so they get a single-pick
    // launcher instead of the multi-pick one — the picker itself only lets
    // the user choose one. Picking a different file replaces the current
    // one; picking the same one again is a no-op.
    val singlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) vm.replaceMedia(uri)
    }

    // A story takes one photo or one video, so it gets the visual-media
    // picker (photos and videos together) instead of the image-only one.
    val storyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) vm.replaceMedia(uri)
    }

    if (done) {
        onDone()
        vm.resetDone()
    }

    // Stories carry no caption even if text was typed before switching
    // type, so nothing stale is saved with a story.
    fun storySafe(text: String) = if (type == PostType.STORY) "" else text

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text(if (editPost != null) "Edit post" else "Create", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        // Posting to — Page avatar + name.
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PageAvatar(
                    pictureUrl = pagePictureUrl,
                    name = pageName ?: "",
                    size = 40.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Posting to", style = MaterialTheme.typography.labelMedium)
                    Text(
                        pageName ?: "No Page connected",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
                // Switch opens a dropdown listing the connected Pages so
                // switching is one tap, no trip to the Page screen. With no
                // Page connected there is nothing to pick from, so it goes
                // to the Page screen to connect one instead.
                Box {
                    TextButton(onClick = {
                        if (pageName == null || pages.isEmpty()) {
                            onConnectPage()
                        } else {
                            vm.refreshPages()
                            pageMenuExpanded = true
                        }
                    }) {
                        Text(if (pageName == null) "Connect" else "Switch")
                    }
                    DropdownMenu(
                        expanded = pageMenuExpanded,
                        onDismissRequest = { pageMenuExpanded = false }
                    ) {
                        pages.forEach { page ->
                            DropdownMenuItem(
                                text = { Text(page.name) },
                                leadingIcon = {
                                    PageAvatar(
                                        pictureUrl = page.pictureUrl,
                                        name = page.name,
                                        size = 24.dp
                                    )
                                },
                                trailingIcon = {
                                    if (page.id == selectedPageId) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "Selected"
                                        )
                                    }
                                },
                                onClick = {
                                    vm.selectPage(page)
                                    pageMenuExpanded = false
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Connect more pages…") },
                            onClick = {
                                pageMenuExpanded = false
                                onConnectPage()
                            }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        Text("Type", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PostType.entries.forEachIndexed { index, t ->
                SegmentedButton(
                    selected = type == t,
                    onClick = { vm.setType(t) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index, count = PostType.entries.size
                    ),
                    label = {
                        Text(t.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // Stories have no caption — only posts and reels do. Hashtags go
        // in the caption.
        if (type != PostType.STORY) {
            OutlinedTextField(
                value = caption, onValueChange = { caption = it },
                label = { Text("Caption") },
                placeholder = { Text("Write a caption…") },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(20.dp))
        }

        Text("Media", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            when (type) {
                PostType.REEL -> singlePicker.launch("video/*")
                PostType.STORY -> storyPicker.launch(
                    ActivityResultContracts.PickVisualMedia.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageAndVideo
                    )
                )
                else -> picker.launch("image/*")
            }
        }) {
            Icon(
                if (type == PostType.REEL) Icons.Default.Videocam else Icons.Default.Image,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (type == PostType.REEL) "Pick video"
                else if (type == PostType.STORY) "Pick photo or video"
                else "Pick photos"
            )
        }
        if (pickedMedia.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            MediaRow(
                items = pickedMedia.map {
                    com.postscheduler.ui.components.ComposerMedia(
                        uri = it.uri.toString(),
                        uploading = it.state == CreateViewModel.UploadState.UPLOADING,
                        failed = it.state == CreateViewModel.UploadState.FAILED
                    )
                },
                isVideo = type == PostType.REEL || pickedMedia.any { it.isVideo },
                onRemove = { uri ->
                    pickedMedia.firstOrNull { it.uri.toString() == uri }?.let { vm.removeMedia(it) }
                },
                onRetry = { uri ->
                    pickedMedia.firstOrNull { it.uri.toString() == uri }?.let { vm.retryUpload(it) }
                },
                dimAfter = if (type == PostType.STORY) 1 else Int.MAX_VALUE
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (type == PostType.STORY && pickedMedia.size > 1)
                    "A story uses one file only — the first one is kept. Remove the extra files to continue."
                else if (hasPendingUploads) "Uploading…"
                else "${pickedMedia.size} new selected — these replace the current photos on save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (editPost != null && editPost.mediaList.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            MediaRow(
                items = editPost.mediaList.map {
                    com.postscheduler.ui.components.ComposerMedia(uri = it)
                },
                isVideo = type == PostType.REEL || editPost.mediaKind == "video",
                dimAfter = if (type == PostType.STORY) 1 else Int.MAX_VALUE
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (type == PostType.STORY && editPost.mediaList.size > 1)
                    "This post has ${editPost.mediaList.size} photos — a story uses one file only. Pick a new one to replace them."
                else "${editPost.mediaList.size} photo${if (editPost.mediaList.size > 1) "s" else ""} on this post — pick new ones to replace them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(20.dp))

        Text("Schedule", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Schedule, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (saveAsDraft || schedule == null) "Draft" else "Scheduled",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        schedule?.let {
                            SimpleDateFormat("HH:mm, d MMM", Locale.getDefault())
                                .format(Date(it)) + if (saveAsDraft) " (planned — draft)" else ""
                        } ?: "No time — saves as a draft",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                TextButton(onClick = { showDatePicker = true }) { Text("Change") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedButton(onClick = { schedule = vm.defaultScheduleMillis(); saveAsDraft = false }) {
                Text("Next hour")
            }
            OutlinedButton(
                // Keeps any picked time as the draft's planned time — it
                // does NOT clear the time.
                onClick = { saveAsDraft = true },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("Save as draft") }
        }
        scheduleError?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { vm.save(storySafe(caption), schedule, publishNow = false, saveAsDraft = saveAsDraft) },
            enabled = !saving && !hasPendingUploads, modifier = Modifier.fillMaxWidth()
        ) { Text(if (editPost != null) "Save changes" else if (saveAsDraft || schedule == null) "Save draft" else "Add to Queue") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { vm.save(storySafe(caption), null, publishNow = true) },
            enabled = !saving && !hasPendingUploads, modifier = Modifier.fillMaxWidth()
        ) { Text("Publish now") }
        Spacer(Modifier.height(8.dp))
        if (pageName == null) {
            Text(
                "Posts save locally without a Page, but nothing can publish until one is connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickedDateMillis = dateState.selectedDateMillis
                    showDatePicker = false
                    showTimePicker = true
                }) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) { DatePicker(state = dateState) }
    }
    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val day = pickedDateMillis ?: System.currentTimeMillis()
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = day
                        set(Calendar.HOUR_OF_DAY, timeState.hour)
                        set(Calendar.MINUTE, timeState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val picked = cal.timeInMillis
                    if (picked < System.currentTimeMillis() + 2 * 60 * 1000) {
                        scheduleError = "Pick a time at least 2 minutes ahead."
                    } else {
                        schedule = picked
                        saveAsDraft = false
                        scheduleError = null
                    }
                    showTimePicker = false
                }) { Text("Set time") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
            text = { TimePicker(state = timeState) }
        )
    }
}
