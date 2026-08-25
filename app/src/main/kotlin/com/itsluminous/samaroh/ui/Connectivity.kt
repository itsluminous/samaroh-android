package com.itsluminous.samaroh.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Observes default-network availability for the offline banner slot (§4.5).
 * Deliberately minimal: the sync engine (W1-E) owns real reachability semantics.
 */
@Composable
fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current
    val isOnline = remember { mutableStateOf(true) }

    DisposableEffect(context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        fun currentlyOnline(): Boolean =
            manager.activeNetwork
                ?.let(manager::getNetworkCapabilities)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        isOnline.value = currentlyOnline()

        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    isOnline.value = true
                }

                override fun onLost(network: Network) {
                    isOnline.value = currentlyOnline()
                }
            }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        manager.registerNetworkCallback(request, callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }

    return isOnline
}
