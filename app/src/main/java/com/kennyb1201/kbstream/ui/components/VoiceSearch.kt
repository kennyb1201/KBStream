package com.kennyb1201.kbstream.ui.components

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusGlowSmall
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * Builds the voice-search intent targeted at the device's DEFAULT speech
 * recognizer (the one chosen under Settings > Language & input > Voice
 * input), instead of firing a bare ACTION_RECOGNIZE_SPEECH that opens the
 * "complete action using" chooser. Picking the wrong chooser entry there
 * (e.g. the Google app rather than the voice recognition service) used to
 * swallow the session: green mic and listening sounds, but no transcript
 * ever returned to KBStream.
 *
 * Shared by every search box in the app (the global search screen and the
 * guide's channel/program overlay) so they all reach the same recognizer
 * the same way.
 */
internal fun voiceSearchIntent(
    context: Context,
    prompt: String = "Search KBStream"
): Intent {
    val base = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        // The hint the recognizer shows while listening.
        putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
    }

    // Settings.Secure "voice_recognition_service" is the ComponentName
    // ("pkg/cls") of the system's current default RecognitionService. Its
    // package hosts the recognizer activity too, so scoping the intent to
    // that package routes the session straight to it — no chooser, no
    // wrong-handler dead end. If anything is unreadable or uninstalled, fall
    // back to the bare intent (chooser) so voice search still works, just the
    // old way.
    val defaultRecognizerPackage = runCatching {
        Settings.Secure.getString(
            context.contentResolver,
            "voice_recognition_service"
        )
    }.getOrNull()
        ?.substringBefore("/")
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?: return base

    val scoped = Intent(base).apply {
        setPackage(defaultRecognizerPackage)
    }
    // If the default recognizer package can't resolve the activity
    // (uninstalled/changed), fall back to the chooser-style intent.
    val resolves = runCatching {
        context.packageManager.queryIntentActivities(scoped, 0)
    }.getOrDefault(emptyList())
    if (resolves.isEmpty()) {
        return base
    }
    return scoped
}

/**
 * Whether an in-app voice search can run on this device at all.
 *
 * Fire OS ships no speech recognizer, and Amazon's own guidance is explicit:
 * the Leanback search flow's speech callback "produces an error" on Fire TV
 * because "Fire TV does not support this speech recognizer" (voice search there
 * is Alexa, which is system-level and cannot return a transcript to an app).
 * The chip was drawn anyway, so on a Fire TV Stick its only reward for a press
 * was nothing at all - the reported "voice search just doesn't work".
 *
 * Answered by what the platform will actually resolve rather than by
 * `Build.MANUFACTURER`, so any box without a recognizer (a bare AOSP stick, a
 * stripped TV ROM) drops the chip for the same reason, and a Fire OS build that
 * ever does ship one gets it back for free.
 */
internal fun voiceSearchAvailable(context: Context): Boolean =
    runCatching {
        context.packageManager.queryIntentActivities(voiceSearchIntent(context), 0)
    }.getOrDefault(emptyList()).isNotEmpty()

/**
 * The voice-search launcher both search boxes drive. Returns a function that
 * fires the recognizer and reports whether it was actually dispatched —
 * false means no recognizer is installed, which is the caller's cue to fall
 * back to the software keyboard instead of leaving the user on a dead chip.
 *
 * A transcript that comes back empty (RESULT_OK with no matches, or Cancel)
 * is ignored rather than clearing the field.
 */
@Composable
internal fun rememberVoiceSearch(
    onTranscript: (String) -> Unit,
    prompt: String = "Search KBStream"
): (Context) -> Boolean {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isNotEmpty()) {
            onTranscript(spoken)
        }
    }
    return { context ->
        runCatching { launcher.launch(voiceSearchIntent(context, prompt)) }.isSuccess
    }
}

/**
 * The "Voice search" chip as it appears under a search field: the same
 * focused Surface treatment as the app's other focusable chips (raised
 * surface + accent content + border + glow) so it lights up consistently on
 * D-pad focus. Shared so the guide's overlay and the global search screen
 * cannot drift apart.
 *
 * @param onTranscript the recognized text, already trimmed and non-empty.
 * @param prompt the hint the recognizer shows while listening.
 * @param onUnavailable called when no recognizer could handle the intent.
 */
@Composable
internal fun VoiceSearchChip(
    onTranscript: (String) -> Unit,
    modifier: Modifier = Modifier,
    prompt: String = "Search KBStream",
    onUnavailable: () -> Unit = {}
) {
    val context = LocalContext.current
    val launchVoiceSearch = rememberVoiceSearch(onTranscript, prompt)

    Surface(
        onClick = {
            if (!launchVoiceSearch(context)) {
                // No recognizer installed on this device.
                onUnavailable()
            }
        },
        shape = ClickableSurfaceDefaults.shape(
            shape = KBShapeChip
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = KBSurfaceRaised,
            contentColor = KBTextLo,
            focusedContainerColor = KBAccent,
            focusedContentColor = KBVoid,
            pressedContainerColor = KBAccent,
            pressedContentColor = KBVoid
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = KBFocusChip,
            pressedScale = KBFocusPressed
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, KBTextLo.copy(alpha = 0.35f)),
                shape = KBShapeChip
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = KBShapeChip
            )
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = KBAccent,
                elevation = KBFocusGlowSmall
            )
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Voice search",
                // tv Surface drives tv-material's LocalContentColor, not
                // material3's — read it explicitly so the mic follows the
                // same KBTextLo -> KBVoid flip as the label on focus.
                tint = LocalContentColor.current,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Voice search",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 7.dp)
            )
        }
    }
}
