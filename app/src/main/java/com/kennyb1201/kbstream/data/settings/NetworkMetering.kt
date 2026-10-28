package com.kennyb1201.kbstream.data.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the connection this device is using is METERED - a phone's data plan,
 * a hotspot, a metered Wi-Fi network - as opposed to the unmetered one the rest
 * of the app assumes.
 *
 * Android already answers this exactly (`NET_CAPABILITY_NOT_METERED` is false
 * for a connection the OS considers metered, and a user can mark even a Wi-Fi
 * network as metered in system settings), so this asks rather than guessing
 * from "is it Wi-Fi". The app never sniffed the network before the cellular
 * data-saver, which is why a 4K auto-play could start on a phone's data plan
 * with nothing having looked.
 *
 * Unknown reads as NOT metered, deliberately: a device whose connectivity
 * service cannot be asked (or that reports no active network) must behave
 * exactly as it did before this existed, and a data saver that silently capped
 * quality on a wired connection would be a worse bug than the one it fixes.
 */
object NetworkMetering {

    fun isMetered(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = runCatching { manager.activeNetwork }.getOrNull() ?: return false
        val capabilities = runCatching { manager.getNetworkCapabilities(network) }
            .getOrNull() ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
