package ai.hindidub.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * One question, answered cheaply: is there a usable network right now?
 *
 * Used by the engine router before every job. Deliberately conservative — no captive-portal
 * probing, no DNS check — because the cloud stages already fall back to local when a request
 * fails, and a wrong "online" answer costs one failed request, not a broken job.
 */
object NetworkState {

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
