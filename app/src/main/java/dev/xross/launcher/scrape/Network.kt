package dev.xross.launcher.scrape

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Network {
    /** True on an unmetered connection (Wi-Fi, Ethernet) that reaches the internet. */
    fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
