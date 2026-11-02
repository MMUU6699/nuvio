package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio

@Composable
fun VideoQualityModal(
    visible: Boolean,
    qualityTracks: List<VideoQualityTrack>,
    selectedHeight: Int,
    onQualitySelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val effectiveTracks = if (qualityTracks.isNotEmpty()) {
        qualityTracks
    } else {
        val h = if (selectedHeight > 0 && selectedHeight < Int.MAX_VALUE) selectedHeight else 1080
        val label = when {
            h >= 2160 -> "4K Ultra HD (${h}p)"
            h >= 1080 -> "1080p Full HD"
            h >= 720 -> "720p HD"
            h >= 480 -> "480p SD"
            else -> "${h}p"
        }
        listOf(
            VideoQualityTrack(
                index = 1,
                id = "current_stream_q",
                label = "$label • الجودة المتوفرة",
                height = h,
                width = h * 16 / 9,
                isSelected = true,
                isAuto = false,
            )
        )
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 64.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val railWidth = minOf(maxWidth, 444.dp)
            val railMaxHeight = (maxHeight - 64.dp).coerceAtLeast(120.dp).coerceAtMost(620.dp)

            Column(
                modifier = Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    .align(Alignment.BottomStart),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = "جودة الفيديو",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = railMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(effectiveTracks, key = { "${it.index}:${it.height}:${it.id}" }) { track ->
                        val isSelected = if (track.isAuto) {
                            selectedHeight == Int.MAX_VALUE || selectedHeight <= 0
                        } else {
                            selectedHeight == track.height
                        }
                        VideoQualityRow(
                            track = track,
                            isSelected = isSelected,
                            onClick = {
                                onQualitySelected(if (track.isAuto) Int.MAX_VALUE else track.height)
                            },
                        )
                    }
                }
            }
        }
    }
}

internal fun defaultVideoQualityOptions(selectedHeight: Int): List<VideoQualityTrack> = listOf(
    VideoQualityTrack(
        index = 0,
        id = "auto",
        label = "تلقائي (Auto) • حسب سرعة الإنترنت",
        height = Int.MAX_VALUE,
        isSelected = selectedHeight == Int.MAX_VALUE || selectedHeight <= 0,
        isAuto = true,
    ),
    VideoQualityTrack(
        index = 1,
        id = "2160p",
        label = "4K Ultra HD (2160p) • دقة فائقة الجودة",
        height = 2160,
        width = 3840,
        isSelected = selectedHeight == 2160,
    ),
    VideoQualityTrack(
        index = 2,
        id = "1080p",
        label = "1080p Full HD • دقة فائقة الوضوح",
        height = 1080,
        width = 1920,
        isSelected = selectedHeight == 1080,
    ),
    VideoQualityTrack(
        index = 3,
        id = "720p",
        label = "720p HD • دقة عالية",
        height = 720,
        width = 1280,
        isSelected = selectedHeight == 720,
    ),
    VideoQualityTrack(
        index = 4,
        id = "480p",
        label = "480p SD • متوسطة (توفير البيانات)",
        height = 480,
        width = 854,
        isSelected = selectedHeight == 480,
    ),
    VideoQualityTrack(
        index = 5,
        id = "360p",
        label = "360p • منخفضة (للإنترنت الضعيف)",
        height = 360,
        width = 640,
        isSelected = selectedHeight == 360,
    ),
)

@Composable
private fun VideoQualityRow(
    track: VideoQualityTrack,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val primaryColor = if (isSelected) tokens.colors.onAccent else Color.White
    val secondaryColor = if (isSelected) {
        tokens.colors.onAccent.copy(alpha = 0.82f)
    } else {
        Color.White.copy(alpha = 0.72f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) tokens.colors.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = track.label,
                color = primaryColor,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!track.isAuto && track.bitrate > 0) {
                val mbps = ((track.bitrate / 100_000) / 10.0)
                Text(
                    text = "${track.width.takeIf { it > 0 } ?: ""}x${track.height} • $mbps Mbps",
                    color = secondaryColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = tokens.colors.onAccent,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(18.dp),
            )
        }
    }
}
