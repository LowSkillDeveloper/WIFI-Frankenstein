package com.lsd.wififrankenstein.util

class KalmanFilter(qMetersPerSec: Float = 3.0f) {

    private val minAccuracy = 1f
    private var qMetersPerSecond = qMetersPerSec
    private var timeStampMilliseconds = 0L
    private var lat = 0.0
    private var lng = 0.0
    private var variance = -1f

    val isInitialized: Boolean get() = variance >= 0

    val accuracy: Float get() = Math.sqrt(variance.toDouble()).toFloat()

    val latitude: Double get() = lat

    val longitude: Double get() = lng

    fun reset() {
        variance = -1f
    }

    fun setState(lat: Double, lng: Double, accuracy: Float, timeStampMs: Long) {
        this.lat = lat
        this.lng = lng
        this.variance = accuracy * accuracy
        this.timeStampMilliseconds = timeStampMs
    }

    fun process(
        latMeasurement: Double,
        lngMeasurement: Double,
        accuracy: Float,
        timeStampMs: Long
    ) {
        var acc = accuracy
        if (acc < minAccuracy) acc = minAccuracy
        if (variance < 0) {
            timeStampMilliseconds = timeStampMs
            lat = latMeasurement
            lng = lngMeasurement
            variance = acc * acc
        } else {
            val timeIncMs = timeStampMs - timeStampMilliseconds
            if (timeIncMs > 0) {
                variance += timeIncMs * qMetersPerSecond * qMetersPerSecond / 1000
                timeStampMilliseconds = timeStampMs
            }
            val k = variance / (variance + acc * acc)
            lat += k * (latMeasurement - lat)
            lng += k * (lngMeasurement - lng)
            variance = (1 - k) * variance
        }
    }
}
