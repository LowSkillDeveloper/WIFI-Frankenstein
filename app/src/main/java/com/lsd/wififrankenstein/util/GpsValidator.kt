package com.lsd.wififrankenstein.util

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

        if (location.isFromMockProvider) return GpsHealth.BAD

        var suspicious = false

        lastLocation?.let { last ->
            val distance = last.distanceTo(location)
            val timeDiff = (location.time - last.time) / 1000.0
            if (timeDiff > 0) {
                val speed = distance / timeDiff
                val accuracySum = last.accuracy + location.accuracy

                if (speed > 300 + accuracySum / maxOf(timeDiff, 1.0)) {
                    suspicious = true
                }
            }
        }

        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return GpsHealth.BAD
        if (location.latitude == 0.0 && location.longitude == 0.0) return GpsHealth.BAD
        if (location.accuracy > 16000f) return GpsHealth.BAD

        if (location.accuracy <= 0f || location.time == 0L) {
            return GpsHealth.UNRELIABLE
        }
        lastLocation = location

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

                if (usedInFixCount < 4 || avgCn0 < 15f || avgCn0 > 55f) {
                    suspicious = true
                }

                if (usedInFixCount >= 8 && avgCn0 > 25f && !suspicious) return GpsHealth.EXCELLENT
                if (usedInFixCount >= 4 && avgCn0 > 20f && !suspicious) return GpsHealth.GOOD
            }
        }

        if (location.accuracy > 100f) return GpsHealth.BAD
        if (suspicious) return GpsHealth.UNRELIABLE

        return GpsHealth.GOOD
    }

    fun getSatelliteCount(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {

            return gnssStatus?.let { status ->
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) used++
                }
                used
            } ?: 0
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
