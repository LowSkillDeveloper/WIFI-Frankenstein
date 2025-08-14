package com.lsd.wififrankenstein.ui.wifimap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.lsd.wififrankenstein.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.MutableLiveData
import org.osmdroid.util.GeoPoint

class UserLocationManager(private val context: Context) : LocationListener {
    private val TAG = "UserLocationManager"
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _userLocation = MutableLiveData<GeoPoint?>()
    val userLocation = _userLocation

    private val _locationError = MutableLiveData<String>()
    val locationError = _locationError

    private var isLocationRequested = false

    fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            _locationError.postValue("Location permission required")
            return
        }

        try {
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!isGpsEnabled && !isNetworkEnabled) {
                _locationError.postValue("Location services disabled")
                return
            }

            isLocationRequested = true

            if (isGpsEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    60000L,
                    10f,
                    this
                )
            }

            if (isNetworkEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    60000L,
                    10f,
                    this
                )
            }

            val lastKnownLocation = getLastKnownLocation()
            lastKnownLocation?.let {
                _userLocation.postValue(GeoPoint(it.latitude, it.longitude))
            }

        } catch (e: SecurityException) {
            _locationError.postValue("Location permission denied")
        } catch (e: Exception) {
            _locationError.postValue("Error getting location: ${e.message}")
        }
    }

    fun requestSingleLocationUpdate() {
        Log.d(TAG, "requestSingleLocationUpdate called")

        if (!hasLocationPermission()) {
            val message = "Location permission required"
            Log.w(TAG, message)
            _locationError.postValue(message)
            return
        }

        try {
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            Log.d(TAG, "GPS enabled: $isGpsEnabled, Network enabled: $isNetworkEnabled")

            if (!isGpsEnabled && !isNetworkEnabled) {
                val message = "Location services disabled"
                Log.w(TAG, message)
                _locationError.postValue(message)
                return
            }

            Log.d(TAG, "Requesting location updates")

            if (isGpsEnabled) {
                Log.d(TAG, "Requesting GPS location")
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, this, null)
            }

            if (isNetworkEnabled) {
                Log.d(TAG, "Requesting network location")
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, this, null)
            }

        } catch (e: SecurityException) {
            val message = "Location permission denied"
            Log.e(TAG, message, e)
            _locationError.postValue(message)
        } catch (e: Exception) {
            val message = "Error getting location: ${e.message}"
            Log.e(TAG, message, e)
            _locationError.postValue(message)
        }
    }

    private fun getLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null

        try {
            val gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            return when {
                gpsLocation != null && networkLocation != null -> {
                    if (gpsLocation.time > networkLocation.time) gpsLocation else networkLocation
                }
                gpsLocation != null -> gpsLocation
                networkLocation != null -> networkLocation
                else -> null
            }
        } catch (e: SecurityException) {
            return null
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun stopLocationUpdates() {
        try {
            if (isLocationRequested) {
                locationManager.removeUpdates(this)
                isLocationRequested = false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Error stopping location updates", e)
        }
    }

    override fun onLocationChanged(location: Location) {
        Log.d(TAG, "Location updated: ${location.latitude}, ${location.longitude}")
        _userLocation.postValue(GeoPoint(location.latitude, location.longitude))
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}