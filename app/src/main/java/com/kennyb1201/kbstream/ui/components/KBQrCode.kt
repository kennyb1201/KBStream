package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The verification address as a scannable code, for a viewer on a couch.
 *
 * The device-code flow asks them to type a URL with a remote; a phone camera is
 * the same step without the keyboard. The code is drawn at [size] (200dp is the
 * floor a TV camera can resolve from across a room) on white, because ZXing's
 * output is black-on-white only and the panel behind it is dark. The URL is
 * printed underneath for anyone who would rather type it.
 *
 * The QR replaces typing the URL, never the code: neither Trakt's nor Simkl's
 * activation page pre-fills a code from a query parameter, so the code stays
 * large and separate beside this panel.
 */
@Composable
fun KBQrCode(
    url: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp
) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    // Once per URL/size: regenerating a several-hundred-thousand-pixel bitmap on
    // every recomposition is the one thing this panel must not do.
    val bitmap = remember(url, sizePx) { qrcodeBitmap(url, sizePx) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(KBSurfaceRaised, KBShapeCard)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "SCAN TO OPEN",
            color = KBAccent,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR code for $url",
                modifier = Modifier
                    .padding(top = 10.dp)
                    .size(size)
                    .background(Color.White, KBShapeSmall)
                    .padding(6.dp)
            )
        }

        Text(
            text = url,
            color = KBTextHi,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            text = "Enter the code on the page this opens.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
