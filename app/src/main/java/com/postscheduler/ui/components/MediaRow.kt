package com.postscheduler.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

// One tile in the composer media row. `uploading` shows a circular
// progress overlay while the file goes up in the background; `failed`
// shows a retry button instead.
data class ComposerMedia(
    val uri: String,
    val uploading: Boolean = false,
    val failed: Boolean = false
)

@Composable
fun MediaRow(
    items: List<ComposerMedia>,
    isVideo: Boolean = false,
    onRemove: ((String) -> Unit)? = null,
    onRetry: ((String) -> Unit)? = null,
    dimAfter: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val shape = RoundedCornerShape(12.dp)
    LazyRow(
        modifier = modifier.fillMaxWidth().height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(items) { index, item ->
            Box(
                Modifier.size(120.dp).clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .alpha(if (index >= dimAfter) 0.35f else 1f)
            ) {
                AsyncImage(
                    model = item.uri, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
                if (isVideo) {
                    Box(
                        Modifier.align(Alignment.Center).size(44.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PlayArrow, contentDescription = "Video",
                            tint = Color.White, modifier = Modifier.size(28.dp)
                        )
                    }
                }
                if (item.uploading) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color.White, modifier = Modifier.size(36.dp)
                        )
                    }
                } else if (item.failed) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier.size(44.dp)
                                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                                .clickable { onRetry?.invoke(item.uri) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Refresh, contentDescription = "Retry upload",
                                tint = Color.White, modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                if (onRemove != null) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                CircleShape
                            )
                            .clickable { onRemove(item.uri) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Close, contentDescription = "Remove photo",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
