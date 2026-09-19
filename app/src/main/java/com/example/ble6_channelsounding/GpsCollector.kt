package com.example.ble6_channelsounding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

class GpsCollector(private val context: Context, private val log: (String) -> Unit) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val lock = Any()
    private val pending = ArrayDeque<GpsFix>()
    private var listener: LocationListener? = null

    fun start(): Boolean {
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            log("Location permission denied (precise location required)")
            return false
        }
        if (manager == null || !manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            log("GPS provider unavailable")
            return false
        }
        val callback = object : LocationListener {
            private var lastFixNanos = Long.MIN_VALUE
            override fun onLocationChanged(location: Location) {
                synchronized(lock) {
                    if (listener !== this || location.elapsedRealtimeNanos <= lastFixNanos) return
                    lastFixNanos = location.elapsedRealtimeNanos
                    pending.addLast(GpsFix(location.latitude, location.longitude, location.time))
                }
            }
            override fun onProviderDisabled(provider: String) {
                synchronized(lock) { if (listener !== this) return; pending.clear() }
                log("GPS provider disabled; waiting for a new fix")
            }
            override fun onProviderEnabled(provider: String) { log("GPS provider enabled") }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        return try {
            synchronized(lock) { listener = callback }
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, callback, Looper.getMainLooper())
            log(if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) "GPS ON; requested interval=1000 ms" else "GPS provider disabled; waiting for enable")
            true
        } catch (e: Exception) {
            stop(); log("GPS start failed: ${e.message}"); false
        }
    }
    fun poll(): GpsFix? = synchronized(lock) { pending.removeFirstOrNull() }
    fun hasPending(): Boolean = synchronized(lock) { pending.isNotEmpty() }
    fun stop(clearPending: Boolean = true) {
        val old = synchronized(lock) {
            listener.also { listener = null; if (clearPending) pending.clear() }
        }
        old?.let { runCatching { manager?.removeUpdates(it) } }
    }
}
