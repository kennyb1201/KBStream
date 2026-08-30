package com.kennyb1201.kbstream.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focus.FocusRequester
import androidx.compose.foundation.focus.focusProperties
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

@Composable
fun SettingsPanel(
    streamWidth: Int,
    streamHeight: Int,
    streamBitrate: Int,
    streamCodec: String?,
    playbackSpeed: Float,
    resizeModeIndex: Int,
    subtitleOffsetMs: Int,
    subtitleSize: Int,
    externalSubtitleLabel: String?,
    isLiveChannel: Boolean,
    onSubtitleOffsetChange: (Int) -> Unit,
    onSubtitleSizeChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val subtitleSizeOptions = listOf("Small", "Normal", "Large")
    val resizeModeLabels = listOf("Fit", "Zoom", "Fill")

    BackHandler { onDismiss() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBVoid.copy(alpha = 0.75f))
            .focusGroup()
            .focusProperties { exit = { FocusRequester.Cancel } },
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .padding(24.dp)
                .background(KBSurfaceRaised, RoundedCornerShape(16.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "PLAYBACK SETTINGS",
                color = KBAccent,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (streamWidth > 0 && streamHeight > 0) {
                Text(
                    text = "STREAM",
                    color = KBAccent,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                SettingsInfoRow("Resolution", "${streamWidth}x${streamHeight}")
                if (streamBitrate > 0) {
                    SettingsInfoRow("Bitrate", "${streamBitrate / 1_000} kbps")
                }
                streamCodec?.let { SettingsInfoRow("Codec", it) }
                SettingsInfoRow("Speed", "${playbackSpeed}x")
                SettingsInfoRow("Aspect", resizeModeLabels.getOrElse(resizeModeIndex) { "Fit" })
                Spacer(modifier = Modifier.height(12.dp))
            }

            Text(
                text = "SUBTITLES",
                color = KBAccent,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Text(
                text = "Size",
                color = KBTextHi,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                subtitleSizeOptions.forEachIndexed { index, label ->
                    KBCard(
                        onClick = { onSubtitleSizeChange(index) }
                    ) {
                        Text(
                            text = label,
                            color = if (subtitleSize == index) KBVoid else KBTextHi,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .background(
                                    if (subtitleSize == index) KBAccent else KBSurface,
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Offset: ${subtitleOffsetMs}ms",
                color = KBTextHi,
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = subtitleOffsetMs.toFloat(),
                onValueChange = { onSubtitleOffsetChange(it.toInt()) },
                valueRange = -5000f..5000f,
                steps = 19,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = KBAccent,
                    activeTrackColor = KBAccent,
                    inactiveTrackColor = KBSurface
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (!isLiveChannel) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = externalSubtitleLabel ?: "No external subtitle loaded",
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = value,
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
