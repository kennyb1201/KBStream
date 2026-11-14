package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.kennyb1201.kbstream.ui.components.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel as TouchKBDialogPanel

/**
 * The plate an anime tracker's connect dialog draws on (spec 2026-11-13, F2).
 *
 * ONE dialog per service, two plates. The dialogs themselves live in
 * `ui/settings` as `internal`, which is MODULE scope - `ui/mobile` can already
 * call them, so nothing had to move for the phone rows to reach them. What did
 * have to change is the plate: the TV panel is a fixed 560dp wide, and the app's
 * touch twin exists because that width runs off both edges of a phone. So each
 * dialog asks for the plate of the surface it was raised on, and the content -
 * fields, buttons, copy - is the single shared body either way.
 *
 * [phone] is passed by the caller that knows its surface (the TV Settings pane
 * and the phone Settings pane each build their own rows), never sniffed from the
 * device.
 */
@Composable
internal fun AnimeDialogPanel(
    phone: Boolean,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    if (phone) {
        TouchKBDialogPanel(title = title, content = content)
    } else {
        KBDialogPanel(title = title, content = content)
    }
}
