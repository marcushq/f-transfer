package dev.fujitransfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Finds a network that reaches the camera. Sockets must be created from the returned
 * Network's socketFactory: the camera's access point has no internet, so Android would
 * otherwise route traffic over mobile data.
 */
class CameraWifi(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** The Wi-Fi network the phone is already joined to (joined manually in Settings). */
    @Suppress("DEPRECATION")
    fun currentWifi(): Network? = cm.allNetworks.firstOrNull { n ->
        cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /**
     * Asks Android to join an access point whose SSID starts with [ssidPrefix].
     * Android shows its own picker; returns null if the user cancels or nothing is found.
     */
    suspend fun request(ssidPrefix: String, password: String?): Network? =
        request(
            WifiNetworkSpecifier.Builder()
                .setSsidPattern(PatternMatcher(ssidPrefix, PatternMatcher.PATTERN_PREFIX))
                .apply { if (!password.isNullOrEmpty()) setWpa2Passphrase(password) }
                .build()
        )

    /** Joins exactly [ssid], e.g. the network the camera reported over Bluetooth. */
    suspend fun requestExact(ssid: String, password: String?): Network? =
        request(
            WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .apply { if (!password.isNullOrEmpty()) setWpa2Passphrase(password) }
                .build()
        )

    private suspend fun request(spec: WifiNetworkSpecifier): Network? {
        release()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        return withTimeoutOrNull(90_000) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                callback = cb
                cm.requestNetwork(req, cb)
            }
        }
    }

    /** Drops the in-app Wi-Fi request; Android then returns to the usual network. */
    fun release() {
        callback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        callback = null
    }
}
