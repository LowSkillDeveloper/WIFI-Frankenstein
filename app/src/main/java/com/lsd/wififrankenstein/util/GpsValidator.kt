package com.lsd.wififrankenstein.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresApi

class GpsValidator(context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var gnssStatus: GnssStatus? = null
    private var lastLocation: Location? = null

    enum class GpsHealth {
        EXCELLENT, GOOD, UNRELIABLE, BAD
    }

    private val gnssCallback = @RequiresApi(Build.VERSION_CODES.N) object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            gnssStatus = status
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                locationManager.registerGnssStatusCallback(gnssCallback, null)
            } catch (e: SecurityException) {
                Log.e("GpsValidator", "Permission denied for GnssStatus", e)
            }
        }
    }

    fun unregister() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locationManager.unregisterGnssStatusCallback(gnssCallback)
        }
    }

    fun validateLocation(location: Location): GpsHealth {
        var suspicious = false

        // 1. Check speed jumps
        lastLocation?.let { last ->
            val distance = last.distanceTo(location)
            val timeDiff = (location.time - last.time) / 1000.0 // seconds
            if (timeDiff > 0) {
                val speed = distance / timeDiff // m/s
                if (speed > 500) { // > 1800 km/h
                    suspicious = true
                }
            }
        }
        lastLocation = location

        // 2. Check GnssStatus (API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssStatus?.let { status ->
                var usedInFixCount = 0
                var avgCn0 = 0f
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) {
                        usedInFixCount++
                        avgCn0 += status.getCn0DbHz(i)
                    }
                }
                
                if (usedInFixCount > 0) {
                    avgCn0 /= usedInFixCount
                }

                // Criteria for unreliable: too few satellites OR abnormal noise
                if (usedInFixCount < 4 || avgCn0 < 15f || avgCn0 > 55f) {
                    suspicious = true
                }
                
                if (usedInFixCount >= 8 && avgCn0 > 25f && !suspicious) return GpsHealth.EXCELLENT
                if (usedInFixCount >= 4 && avgCn0 > 20f && !suspicious) return GpsHealth.GOOD
            }
        }

        // 3. Fallback for older versions or if GNSS data is missing
        if (location.accuracy > 100f) return GpsHealth.BAD
        if (suspicious) return GpsHealth.UNRELIABLE
        
        return GpsHealth.GOOD
    }

    fun getSatelliteCount(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return gnssStatus?.satelliteCount ?: 0
        }
        return 0
    }

    fun getAverageNoise(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssStatus?.let { status ->
                var sum = 0f
                var count = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) {
                        sum += status.getCn0DbHz(i)
                        count++
                    }
                }
                return if (count > 0) sum / count else 0f
            }
        }
        return 0f
    }
}
