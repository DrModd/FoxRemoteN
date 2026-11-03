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
 * DigiFox, PureFox и DigiNeo публикуют службу "_mpd._tcp" с именем
 * "DigiFox MPD on <hostname>" / "PureFox MPD on <hostname>" /
 * "DigiNeo MPD on <hostname>" — по этому имени его легко отличить
 * от других устройств. (DigiNeo — порт DigiFox на NanoPi NEO3.)
 */
/** Найденный Фокс: адрес ("192.168.1.50") и его имя в сети ("digifox.local", если известно) */
data class FoxFound(val addr: String, val local: String?)

class FoxDiscovery(context: Context) {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Вернёт адрес и имя .local или null, если за отведённое время не нашли */
    suspend fun find(timeoutMs: Long = 8000): FoxFound? = withTimeoutOrNull(timeoutMs) {
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

            fun finish(result: FoxFound?) {
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
                    // имя службы — "DigiFox MPD on <hostname>": отсюда имя Фокса в сети
                    val name = info.serviceName.substringAfterLast(" on ", "").trim()
                        .takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' } }
                    if (text != null) finish(FoxFound(text, name?.let { "$it.local" })) else resolving = false
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
                    if (!resolving && (info.serviceName.contains("DigiFox", ignoreCase = true) ||
                            info.serviceName.contains("PureFox", ignoreCase = true) ||
                            info.serviceName.contains("DigiNeo", ignoreCase = true))) {
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
