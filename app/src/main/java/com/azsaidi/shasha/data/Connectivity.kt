package com.azsaidi.shasha.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Whether the device currently has internet, updated live. */
object Connectivity {
    var online by mutableStateOf(true)
        private set

    fun init(context: Context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        online = isOnline(manager)
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    online = true
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }

                override fun onLost(network: Network) {
                    online = isOnline(manager)
                }
            })
        }
    }

    private fun isOnline(manager: ConnectivityManager): Boolean {
        val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
