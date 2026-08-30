package com.kennyb1201.kbstream.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
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
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAddons: () -> Unit = {},
    onOpenSimkl: () -> Unit = {}
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    var bufferMode by remember { mutableIntStateOf(AppPreferences.getDefaultBufferMode(context)) }
    var subtitleSize by remember { mutableIntStateOf(AppPreferences.getDefaultSubtitleSize(context)) }
    var subtitleBg by remember { mutableIntStateOf(AppPreferences.getDefaultSubtitleBackground(context)) }
    var subtitleOffset by remember { mutableIntStateOf(AppPreferences.getDefaultSubtitleOffset(context)) }
    var enableTunneling by remember { mutableStateOf(AppPreferences.getEnableTunneling(context)) }
    var autoPlayNext by remember { mutableStateOf(AppPreferences.getAutoPlayNext(context)) }
    var decoderMode by remember { mutableIntStateOf(AppPreferences.getDecoderMode(context)) }
    var aspectRatio by remember { mutableIntStateOf(AppPreferences.getDefaultAspectRatio(context)) }

    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .padding(horizontal = 64.dp, vertical = 40.dp)
            .focusRequester(focusRequester)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "SETTINGS",
            color = KBAccent,
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Player defaults \u2014 applied to every new playback session",
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(16.dp))

        // ── INTEGRATIONS ────────────────────────────────────────
        SectionHeader("INTEGRATIONS")

        NavigationRow(
            label = "Add-ons",
            description = "Manage Stremio add-ons and catalogs",
            onClick = onOpenAddons
        )

        NavigationRow(
            label = "Simkl",
            description = "Connect your Simkl account for scrobbling",
            onClick = onOpenSimkl
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ── PLAYBACK ──────────────────────────────────────────────
        SectionHeader("PLAYBACK")

        Text(
            text = "Video Decoder",
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Auto", "FFmpeg Only").forEachIndexed { index, label ->
                KBCard(onClick = {
                    decoderMode = index
                    AppPreferences.setDecoderMode(context, index)
                }) {
                    PillChip(label, decoderMode == index)
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = when (decoderMode) {
                0 -> "Hardware first, falls back to FFmpeg if unsupported"
                1 -> "Use FFmpeg for all decoding (fixes green-tint DV)"
                else -> ""
            },
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall
        )

        Spacer(modifier = Modifier.height(10.dp))

        ToggleRow(
            label = "Auto-play Next Episode",
            description = "Continue to the next episode when one ends",
            checked = autoPlayNext,
            onToggle = {
                autoPlayNext = it
                AppPreferences.setAutoPlayNext(context, it)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ── VIDEO ──────────────────────────────────────────────
        SectionHeader("VIDEO")

        Text(
            text = "Network Buffer",
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Balanced (4K-ready)", "Low Latency").forEachIndexed { index, label ->
                KBCard(onClick = {
                    bufferMode = index
                    AppPreferences.setDefaultBufferMode(context, index)
                }) {
                    PillChip(label, bufferMode == index)
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (bufferMode == 1) "Lower buffer for live IPTV" else "25s/120s buffer \u2014 best for movies & series",
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall
        )

        Spacer(modifier = Modifier.height(10.dp))

        ToggleRow(
            label = "Tunneled Playback",
            description = "Low-latency A/V sync for HDR + AVR setups",
            checked = enableTunneling,
            onToggle = {
                enableTunneling = it
                AppPreferences.setEnableTunneling(context, it)
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Default Aspect Ratio",
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Fit", "Zoom", "Fill").forEachIndexed { index, label ->
                KBCard(onClick = {
                    aspectRatio = index
                    AppPreferences.setDefaultAspectRatio(context, index)
                }) {
                    PillChip(label, aspectRatio == index)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── SUBTITLES ──────────────────────────────────────────
        SectionHeader("SUBTITLES")

        Text(
            text = "Default Size",
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Small", "Normal", "Large").forEachIndexed { index, label ->
                KBCard(onClick = {
                    subtitleSize = index
                    AppPreferences.setDefaultSubtitleSize(context, index)
                }) {
                    PillChip(label, subtitleSize == index)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Default Background",
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("None", "Semi", "Solid").forEachIndexed { index, label ->
                KBCard(onClick = {
                    subtitleBg = index
                    AppPreferences.setDefaultSubtitleBackground(context, index)
                }) {
                    PillChip(label, subtitleBg == index)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Default Offset: ${subtitleOffset}ms",
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall
        )
        androidx.compose.material3.Slider(
            value = subtitleOffset.toFloat(),
            onValueChange = { subtitleOffset = it.toInt() },
            onValueChangeFinished = {
                AppPreferences.setDefaultSubtitleOffset(context, subtitleOffset)
            },
            valueRange = -5000f..5000f,
            steps = 19,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = KBAccent,
                activeTrackColor = KBAccent,
                inactiveTrackColor = KBSurface
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

// ── Helper composables ──────────────────────────────────────────

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
            .padding(horizontal = 14.dp, vertical = 8.dp)
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
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun NavigationRow(
    label: String,
    description: String,
    onClick: () -> Unit
) {
    KBCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KBSurfaceRaised, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
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
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = KBTextLo,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
