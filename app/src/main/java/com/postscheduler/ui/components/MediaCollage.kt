package com.postscheduler.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Media collage: one large photo left, up to two stacked
 * right, with a play badge on video and an optional remove button per
 * photo (composer). A "+N" overlay covers the last thumb when there are
 * more than three.
 */
@Composable
fun MediaCollage(
    uris: List<String>,
    isVideo: Boolean = false,
    onRemove: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (uris.isEmpty()) return
    val shape = RoundedCornerShape(12.dp)
    if (uris.size == 1) {
        Box(modifier.fillMaxWidth().height(180.dp).clip(shape)) {
            AsyncImage(
                model = uris[0], contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            if (isVideo) PlayBadge(Modifier.align(Alignment.Center))
            RemoveButton(uris[0], onRemove, Modifier.align(Alignment.TopEnd))
        }
        return
    }
    Row(modifier.fillMaxWidth().height(180.dp)) {
        Box(
            Modifier.weight(2f).fillMaxHeight().padding(end = 4.dp).clip(shape)
        ) {
            AsyncImage(
                model = uris[0], contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            if (isVideo) PlayBadge(Modifier.align(Alignment.Center))
            RemoveButton(uris[0], onRemove, Modifier.align(Alignment.TopEnd))
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .padding(bottom = if (uris.size > 2) 2.dp else 0.dp).clip(shape)
            ) {
                AsyncImage(
                    model = uris[1], contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
                RemoveButton(uris[1], onRemove, Modifier.align(Alignment.TopEnd))
            }
            if (uris.size > 2) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(top = 2.dp).clip(shape)
                ) {
                    AsyncImage(
                        model = uris[2], contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                    if (uris.size > 3) {
                        Box(
                            Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "+${uris.size - 3}", color = Color.White,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    RemoveButton(uris[2], onRemove, Modifier.align(Alignment.TopEnd))
                }
            }
        }
    }
}

@Composable
private fun PlayBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.PlayArrow, contentDescription = "Video",
            tint = Color.White, modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun RemoveButton(
    uri: String,
    onRemove: ((String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    if (onRemove == null) return
    IconButton(
        onClick = { onRemove(uri) },
        modifier = modifier.padding(4.dp).size(24.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape
            )
    ) {
        Icon(Icons.Default.Close, contentDescription = "Remove photo")
    }
}
