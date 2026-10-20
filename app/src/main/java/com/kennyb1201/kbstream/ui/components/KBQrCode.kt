package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.ui.addons.qrcodeBitmap
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi

/**
 * The verification address as a scannable code, for a viewer on a couch.
 *
 * The device-code flow asks them to type a URL with a remote; a phone camera is
 * the same step without the keyboard. The code is drawn at [size] on white,
 * because ZXing's output is black-on-white only and the panel behind it is dark.
 * The URL is printed underneath, small, for anyone who would rather type it.
 *
 * The panel is content-sized, not full width, so a screen can put it in a Row
 * beside the user code. That is how the TV connect pages draw it: a large QR
 * above the code pushed the code below the fold, and the D-pad could not scroll
 * there. 140dp is the TV default - a QR only carries a short URL, and at that
 * size it still resolves from across a room. [size] stays a parameter so the
 * phone layouts can draw it bigger, where the page scrolls by finger.
 *
 * The QR replaces typing the URL, never the code: neither Trakt's nor Simkl's
 * activation page pre-fills a code from a query parameter, so the code stays
 * large and separate beside this panel.
 */
@Composable
fun KBQrCode(
    url: String,
    modifier: Modifier = Modifier,
    size: Dp = 140.dp
) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    // Once per URL/size: regenerating a several-hundred-thousand-pixel bitmap on
    // every recomposition is the one thing this panel must not do.
    val bitmap = remember(url, sizePx) { qrcodeBitmap(url, sizePx) }

    Column(
        modifier = modifier
            .background(KBSurfaceRaised, KBShapeCard)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "SCAN TO OPEN",
            color = KBAccent,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR code for $url",
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(size)
                    .background(Color.White, KBShapeSmall)
                    .padding(6.dp)
            )
        }

        Text(
            text = url,
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
