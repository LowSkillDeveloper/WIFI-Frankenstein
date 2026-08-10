package com.lsd.wififrankenstein.ui.airodump

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import com.lsd.wififrankenstein.util.Log
import org.osmdroid.util.GeoPoint

class CaptureLocationProvider(private val context: Context) {

    private val tag = "CaptureLocationProvider"
    private var fusedClient: FusedLocationProviderClient? = null
    private var cancellationTokenSource: CancellationTokenSource? = null

    private var lastLocation: GeoPoint? = null

    fun start() {
        if (!hasLocationPermission()) {
            Log.d(tag, "No location permission")
            return
        }
        try {
            fusedClient = LocationServices.getFusedLocationProviderClient(context)
            cancellationTokenSource = CancellationTokenSource()
            fusedClient?.getCurrentLocation(
                com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cancellationTokenSource?.token
            )?.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    lastLocation = GeoPoint(location.latitude, location.longitude)
                    Log.d(tag, "Location: ${location.latitude}, ${location.longitude}")
                }
            }?.addOnFailureListener { e ->
                Log.w(tag, "Failed to get location: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(tag, "Location service error: ${e.message}")
        }
    }

    fun stop() {
        cancellationTokenSource?.cancel()
        cancellationTokenSource = null
        fusedClient = null
    }

    fun getLastKnownLocation(): GeoPoint? = lastLocation

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
