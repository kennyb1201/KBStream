package com.kennyb1201.kbstream.data.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions as SdkCastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * The sender's options, named by the manifest's `OPTIONS_PROVIDER_CLASS_NAME`
 * meta-data - CastContext reads this before it will talk to a device.
 *
 * One line of configuration on purpose: the receiver is Play's DEFAULT media
 * receiver, so there is no receiver application of ours to name, no branded
 * namespace to join and no queueing protocol to reimplement. The phone loads a
 * URL onto the television and the television plays it; everything the app knows
 * about the session (position, what to write to history) is read back from the
 * remote player by [CastSender].
 */
class CastOptions : OptionsProvider {

    override fun getCastOptions(context: Context): SdkCastOptions =
        SdkCastOptions.Builder()
            .setReceiverApplicationId(DEFAULT_RECEIVER_ID)
            .build()

    /**
     * No extra session providers: the sender speaks Cast only. Audio-only
     * routes and the media notification's own provider are not in play here.
     */
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    internal companion object {
        /**
         * Play's default media receiver. Fixed by the SDK, and the reason a
         * cast can only ever be a URL: this receiver has no code of ours in it.
         */
        const val DEFAULT_RECEIVER_ID = "CC1AD845"
    }
}
