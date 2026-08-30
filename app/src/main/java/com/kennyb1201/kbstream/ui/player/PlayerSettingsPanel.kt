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
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    subtitleBackground: Int,
    externalSubtitleLabel: String?,
    isLiveChannel: Boolean,
    enableTunneling: Boolean,
    bufferMode: Int,
    autoPlayNext: Boolean,
    onSubtitleOffsetChange: (Int) -> Unit,
    onSubtitleSizeChange: (Int) -> Unit,
    onSubtitleBackgroundChange: (Int) -> Unit,
    onTunnelingChange: (Boolean) -> Unit,
    onBufferModeChange: (Int) -> Unit,
    onAutoPlayNextChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val subtitleSizeOptions = listOf("Small", "Normal", "Large")
    val subtitleBgOptions = listOf("None", "Semi", "Solid")
    val bufferModeOptions = listOf("Balanced", "Low Latency")
    val resizeModeLabels = listOf("Fit", "Zoom", "Fill")

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    BackHandler { onDismiss() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBVoid.copy(alpha = 0.75f))
            .focusGroup(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .padding(24.dp)
                .background(KBSurfaceRaised, RoundedCornerShape(16.dp))
                .padding(20.dp)
                .focusRequester(focusRequester)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "PLAYBACK SETTINGS",
                color = KBAccent,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(16.dp))

            // ── STREAM ──────────────────────────────────────────────
            if (streamWidth > 0 && streamHeight > 0) {
                SectionHeader("STREAM")
                SettingsInfoRow("Resolution", normalizeResolution(streamWidth, streamHeight))
                if (streamBitrate > 0) {
                    SettingsInfoRow("Bitrate", "${streamBitrate / 1_000} kbps")
                }
                val codecLabel = normalizeCodec(streamCodec)
                if (codecLabel != "—") SettingsInfoRow("Codec", codecLabel)
                SettingsInfoRow("Speed", "${playbackSpeed}x")
                SettingsInfoRow("Aspect", resizeModeLabels.getOrElse(resizeModeIndex) { "Fit" })
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── SUBTITLES ──────────────────────────────────────────
            SectionHeader("SUBTITLES")

            Text(
                text = "Size",
                color = KBTextHi,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                subtitleSizeOptions.forEachIndexed { index, label ->
                    KBCard(onClick = { onSubtitleSizeChange(index) }) {
                        PillChip(label, subtitleSize == index)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Background",
                color = KBTextHi,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                subtitleBgOptions.forEachIndexed { index, label ->
                    KBCard(onClick = { onSubtitleBackgroundChange(index) }) {
                        PillChip(label, subtitleBackground == index)
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
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = externalSubtitleLabel ?: "No external subtitle loaded",
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── VIDEO ──────────────────────────────────────────────
            SectionHeader("VIDEO")

            Text(
                text = "Network Buffer",
                color = KBTextHi,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bufferModeOptions.forEachIndexed { index, label ->
                    KBCard(onClick = { onBufferModeChange(index) }) {
                        PillChip(label, bufferMode == index)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (bufferMode == 1) "Lower buffer for live content" else "Best for most streams",
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )

            Spacer(modifier = Modifier.height(10.dp))

            ToggleRow(
                label = "Tunneled Playback",
                description = "Low-latency A/V sync for HDR + AVR",
                checked = enableTunneling,
                onToggle = onTunnelingChange
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ── PLAYBACK ──────────────────────────────────────────
            SectionHeader("PLAYBACK")

            ToggleRow(
                label = "Auto-play Next Episode",
                description = "Continue to the next episode when one ends",
                checked = autoPlayNext,
                onToggle = onAutoPlayNextChange
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = KBAccent,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun PillChip(label: String, selected: Boolean) {
    Text(
        text = label,
        color = if (selected) KBVoid else KBTextHi,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(
                if (selected) KBAccent else KBSurface,
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = KBTextHi,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = description,
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )
        }
        KBCard(onClick = { onToggle(!checked) }) {
            Text(
                text = if (checked) "ON" else "OFF",
                color = if (checked) KBVoid else KBTextHi,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .background(
                        if (checked) KBAccent else KBSurface,
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
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
