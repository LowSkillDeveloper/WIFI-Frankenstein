package com.lsd.wififrankenstein.ui.wifimap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.lsd.wififrankenstein.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.CancellationTokenSource
import org.osmdroid.util.GeoPoint

class UserLocationManager(private val context: Context) : LocationListener {
    private val TAG = "UserLocationManager"
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var currentLocationRequest: LocationRequest? = null

    private val _userLocation = MutableLiveData<GeoPoint?>()
    val userLocation = _userLocation

    private val _locationError = MutableLiveData<String>()
    val locationError = _locationError

    private val _permissionRequired = MutableLiveData<Array<String>>()
    val permissionRequired = _permissionRequired

    private var isLocationRequested = false
    private var singleUpdateCancellation: CancellationTokenSource? = null
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    companion object {
        const val LOCATION_TIMEOUT_MS = 30000L
        const val UPDATE_INTERVAL_MS = 60000L
        const val FASTEST_UPDATE_INTERVAL_MS = 10000L
        const val DISPLACEMENT_THRESHOLD_M = 10f
    }

    init {
        initializeFusedLocationProvider()
    }

    private fun initializeFusedLocationProvider() {
        try {
            if (isGooglePlayServicesAvailable()) {
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                Log.d(TAG, "Using Fused Location Provider")
            } else {
                Log.d(TAG, "Google Play Services not available, using legacy LocationManager")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error initializing Fused Location Provider, falling back to legacy", e)
        }
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val result = googleApiAvailability.isGooglePlayServicesAvailable(context)
        return result == ConnectionResult.SUCCESS
    }

    fun startLocationUpdates() {
        Log.d(TAG, "Starting location updates")

        if (!checkAndRequestPermissions()) {
            return
        }

        if (!areLocationServicesEnabled()) {
            _locationError.postValue("location_services_disabled")
            return
        }

        isLocationRequested = true

        if (fusedLocationClient != null) {
            startFusedLocationUpdates()
        } else {
            startLegacyLocationUpdates()
        }

        getLastKnownLocationWithTimeout()
    }

    private fun startFusedLocationUpdates() {
        try {
            if (!hasLocationPermission()) return

            currentLocationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, UPDATE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL_MS)
                .setMinUpdateDistanceMeters(DISPLACEMENT_THRESHOLD_M)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    val location = locationResult.lastLocation
                    if (location != null) {
                        Log.d(TAG, "Fused location updated: ${location.latitude}, ${location.longitude}")
                        _userLocation.postValue(GeoPoint(location.latitude, location.longitude))
                        cancelTimeout()
                    }
                }

                override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                    if (!locationAvailability.isLocationAvailable) {
                        Log.w(TAG, "Location not available")
                        _locationError.postValue("location_not_available")
                    }
                }
            }

            fusedLocationClient?.requestLocationUpdates(
                currentLocationRequest!!,
                locationCallback!!,
                Looper.getMainLooper()
            )?.addOnSuccessListener {
                Log.d(TAG, "Location updates started successfully")
            }?.addOnFailureListener { e ->
                Log.e(TAG, "Failed to start location updates", e)
                _locationError.postValue("location_updates_failed")
                startLegacyLocationUpdates()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in fused location", e)
            _locationError.postValue("location_permission_denied")
        } catch (e: Exception) {
            Log.e(TAG, "Error in fused location updates", e)
            startLegacyLocationUpdates()
        }
    }

    private fun startLegacyLocationUpdates() {
        try {
            if (!hasLocationPermission()) return

            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            Log.d(TAG, "Legacy location - GPS: $isGpsEnabled, Network: $isNetworkEnabled")

            if (!isGpsEnabled && !isNetworkEnabled) {
                _locationError.postValue("location_services_disabled")
                return
            }

            if (isGpsEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    UPDATE_INTERVAL_MS,
                    DISPLACEMENT_THRESHOLD_M,
                    this
                )
            }

            if (isNetworkEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    UPDATE_INTERVAL_MS,
                    DISPLACEMENT_THRESHOLD_M,
                    this
                )
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in legacy location", e)
            _locationError.postValue("location_permission_denied")
        } catch (e: Exception) {
            Log.e(TAG, "Error in legacy location updates", e)
            _locationError.postValue("location_updates_failed")
        }
    }

    fun requestSingleLocationUpdate() {
        Log.d(TAG, "Requesting single location update")

        if (!checkAndRequestPermissions()) {
            return
        }

        if (!areLocationServicesEnabled()) {
            _locationError.postValue("location_services_disabled")
            return
        }

        if (fusedLocationClient != null) {
            requestSingleFusedLocation()
        } else {
            requestSingleLegacyLocation()
        }

        startLocationTimeout()
    }

    private fun requestSingleFusedLocation() {
        try {
            if (!hasLocationPermission()) return

            singleUpdateCancellation = CancellationTokenSource()

            val currentLocationRequest = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setDurationMillis(LOCATION_TIMEOUT_MS)
                .setMaxUpdateAgeMillis(60000L)
                .build()

            fusedLocationClient?.getCurrentLocation(
                currentLocationRequest,
                singleUpdateCancellation!!.token
            )?.addOnSuccessListener { location ->
                if (location != null) {
                    Log.d(TAG, "Single fused location: ${location.latitude}, ${location.longitude}")
                    _userLocation.postValue(GeoPoint(location.latitude, location.longitude))
                    cancelTimeout()
                } else {
                    Log.w(TAG, "Single fused location returned null")
                    requestSingleLegacyLocation()
                }
            }?.addOnFailureListener { e ->
                Log.e(TAG, "Single fused location failed", e)
                requestSingleLegacyLocation()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in single fused location", e)
            _locationError.postValue("location_permission_denied")
        } catch (e: Exception) {
            Log.e(TAG, "Error in single fused location", e)
            requestSingleLegacyLocation()
        }
    }

    private fun requestSingleLegacyLocation() {
        try {
            if (!hasLocationPermission()) return

            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!isGpsEnabled && !isNetworkEnabled) {
                _locationError.postValue("location_services_disabled")
                return
            }

            val singleLocationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    Log.d(TAG, "Single legacy location: ${location.latitude}, ${location.longitude}")
                    _userLocation.postValue(GeoPoint(location.latitude, location.longitude))
                    locationManager.removeUpdates(this)
                    cancelTimeout()
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            if (isGpsEnabled) {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, singleLocationListener, null)
            }

            if (isNetworkEnabled) {
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, singleLocationListener, null)
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in single legacy location", e)
            _locationError.postValue("location_permission_denied")
        } catch (e: Exception) {
            Log.e(TAG, "Error in single legacy location", e)
            _locationError.postValue("location_updates_failed")
        }
    }

    private fun getLastKnownLocationWithTimeout() {
        if (fusedLocationClient != null) {
            getLastKnownFusedLocation()
        } else {
            getLastKnownLegacyLocation()
        }
    }

    private fun getLastKnownFusedLocation() {
        try {
            if (!hasLocationPermission()) return

            fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
                if (location != null && isLocationRecent(location)) {
                    Log.d(TAG, "Last known fused location: ${location.latitude}, ${location.longitude}")
                    _userLocation.postValue(GeoPoint(location.latitude, location.longitude))
                }
            }?.addOnFailureListener { e ->
                Log.w(TAG, "Failed to get last known fused location", e)
                getLastKnownLegacyLocation()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting last known fused location", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last known fused location", e)
            getLastKnownLegacyLocation()
        }
    }

    private fun getLastKnownLegacyLocation() {
        if (!hasLocationPermission()) return

        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var bestLocation: Location? = null

            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    val location = locationManager.getLastKnownLocation(provider)
                    if (location != null && isLocationRecent(location)) {
                        if (bestLocation == null || location.accuracy < bestLocation.accuracy) {
                            bestLocation = location
                        }
                    }
                }
            }

            bestLocation?.let {
                Log.d(TAG, "Last known legacy location: ${it.latitude}, ${it.longitude}")
                _userLocation.postValue(GeoPoint(it.latitude, it.longitude))
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting last known legacy location", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last known legacy location", e)
        }
    }

    private fun isLocationRecent(location: Location): Boolean {
        val maxAge = 300000L
        return (System.currentTimeMillis() - location.time) < maxAge
    }

    private fun checkAndRequestPermissions(): Boolean {
        val requiredPermissions = getRequiredPermissions()
        val missingPermissions = requiredPermissions.filter { permission ->
            ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.d(TAG, "Missing permissions: $missingPermissions")
            _permissionRequired.postValue(missingPermissions.toTypedArray())
            return false
        }

        return true
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        return permissions
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

    private fun areLocationServicesEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            gpsEnabled || networkEnabled
        }
    }

    private fun startLocationTimeout() {
        cancelTimeout()
        timeoutRunnable = Runnable {
            Log.w(TAG, "Location request timed out")
            _locationError.postValue("location_timeout")
        }
        timeoutHandler.postDelayed(timeoutRunnable!!, LOCATION_TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let {
            timeoutHandler.removeCallbacks(it)
            timeoutRunnable = null
        }
    }

    fun stopLocationUpdates() {
        try {
            if (isLocationRequested) {
                fusedLocationClient?.removeLocationUpdates(locationCallback ?: return)
                locationManager.removeUpdates(this)
                isLocationRequested = false
                Log.d(TAG, "Location updates stopped")
            }

            singleUpdateCancellation?.cancel()
            cancelTimeout()

        } catch (e: SecurityException) {
            Log.e(TAG, "Error stopping location updates", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping location updates", e)
        }
    }

    override fun onLocationChanged(location: Location) {
        Log.d(TAG, "Legacy location updated: ${location.latitude}, ${location.longitude}")
        _userLocation.postValue(GeoPoint(location.latitude, location.longitude))
        cancelTimeout()
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        Log.d(TAG, "Location provider status changed: $provider, status: $status")
    }

    override fun onProviderEnabled(provider: String) {
        Log.d(TAG, "Location provider enabled: $provider")
    }

    override fun onProviderDisabled(provider: String) {
        Log.d(TAG, "Location provider disabled: $provider")
    }

    fun onDestroy() {
        stopLocationUpdates()
    }
}