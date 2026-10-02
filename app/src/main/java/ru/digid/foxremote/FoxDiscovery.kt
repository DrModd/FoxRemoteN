package ru.digid.foxremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import kotlin.coroutines.resume

/**
 * Поиск Фокса в сети по mDNS.
 * PureFox публикует службу "_mpd._tcp" с именем "PureFox MPD on <hostname>" —
 * по этому имени его легко отличить от других устройств.
 */
class FoxDiscovery(context: Context) {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Вернёт адрес для URL ("192.168.1.50") или null, если за отведённое время не нашли */
    suspend fun find(timeoutMs: Long = 8000): String? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            var resolving = false
            var stopped = false

            lateinit var discovery: NsdManager.DiscoveryListener

            fun stop() {
                if (!stopped) {
                    stopped = true
                    try {
                        nsd.stopServiceDiscovery(discovery)
                    } catch (_: Exception) {
                    }
                }
            }

            fun finish(result: String?) {
                stop()
                if (cont.isActive) cont.resume(result)
            }

            val resolve = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    resolving = false
                }

                @Suppress("DEPRECATION")
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val addr = info.host
                    val text = when (addr) {
                        is Inet4Address -> addr.hostAddress
                        is Inet6Address -> "[" + (addr.hostAddress ?: "").replace("%", "%25") + "]"
                        else -> null
                    }
                    if (text != null) finish(text) else resolving = false
                }
            }

            discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onServiceLost(info: NsdServiceInfo) {}

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    stopped = true
                    if (cont.isActive) cont.resume(null)
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

                @Suppress("DEPRECATION")
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (!resolving && info.serviceName.contains("PureFox", ignoreCase = true)) {
                        resolving = true
                        try {
                            nsd.resolveService(info, resolve)
                        } catch (_: Exception) {
                            resolving = false
                        }
                    }
                }
            }

            cont.invokeOnCancellation { stop() }
            nsd.discoverServices("_mpd._tcp", NsdManager.PROTOCOL_DNS_SD, discovery)
        }
    }
}
