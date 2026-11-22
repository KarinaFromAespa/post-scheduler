package com.postscheduler.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.postscheduler.data.local.Post
import com.postscheduler.data.local.PostStatus
import com.postscheduler.data.local.PostType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "Today", "Tomorrow", or e.g. "Fri, Nov 21" for a scheduled time. */
fun dayLabel(millis: Long): String {
    val now = Calendar.getInstance()
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "Today"
    val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
    val isTomorrow = tomorrow.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
        tomorrow.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
    if (isTomorrow) return "Tomorrow"
    return SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(millis))
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

/** "Today · 21:58" — the post's exact scheduled time, as shown on a card. */
fun scheduledTimeLabel(millis: Long): String {
    return "${dayLabel(millis)} · ${timeFmt.format(Date(millis))}"
}

// Status pills use semantic colors (green = published, red = failed) —
// these are states, not the brand color, which stays Facebook blue.
@Composable
private fun statusColors(status: PostStatus): Pair<Color, Color> {
    return when (status) {
        PostStatus.PUBLISHED -> Color(0xFFE6F4EA) to Color(0xFF188038)
        PostStatus.FAILED -> Color(0xFFFCE8E6) to Color(0xFFA50E0E)
        PostStatus.SCHEDULED -> Color(0xFFD6E4FF) to Color(0xFF0A2E5C)
        PostStatus.PUBLISHING -> Color(0xFFFEF7E0) to Color(0xFF8A6D00)
        PostStatus.DRAFT -> Color(0xFFF0F2F5) to Color(0xFF606770)
    }
}

/**
 * Queue card: type + status pills, a 3-dot menu for the
 * per-post actions, a caption snippet, the media collage, and the post's
 * exact scheduled time. The time is on the card itself — the Publish tab
 * groups by day, so no header carries a time for it.
 */
@Composable
fun PostCard(
    post: Post,
    onPublishNow: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onUpdateSchedule: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val (container, label) = statusColors(post.status)

    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(post.type.name) })
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = {},
                    label = { Text(post.status.name) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = container, labelColor = label
                    )
                )
                Spacer(Modifier.weight(1f))
                if (onPublishNow != null || onDelete != null || onRetry != null || onEdit != null || onUpdateSchedule != null) {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Post options")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        onRetry?.let { retry ->
                            DropdownMenuItem(
                                text = { Text("Retry") },
                                onClick = { menuOpen = false; retry() }
                            )
                        }
                        onPublishNow?.let { publishNow ->
                            DropdownMenuItem(
                                text = { Text("Publish now") },
                                onClick = { menuOpen = false; publishNow() }
                            )
                        }
                        onUpdateSchedule?.let { reschedule ->
                            DropdownMenuItem(
                                text = { Text("Update schedule") },
                                onClick = { menuOpen = false; reschedule() }
                            )
                        }
                        onEdit?.let { edit ->
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                onClick = { menuOpen = false; edit() }
                            )
                        }
                        onDelete?.let { delete ->
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = { menuOpen = false; delete() }
                            )
                        }
                    }
                }
            }
            if (post.caption.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    post.caption, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            if (post.hashtags.isNotBlank()) {
                Text(
                    post.hashtags, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (post.mediaList.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                MediaCollage(
                    uris = post.mediaList,
                    isVideo = post.mediaKind == "video" || post.type == PostType.REEL
                )
            }
            post.failureReason?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Failed: $it", maxLines = 2,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            post.scheduledAtMillis?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    scheduledTimeLabel(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
