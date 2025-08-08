package com.lsd.wififrankenstein.ui.wifimap

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.pow
import kotlin.math.sqrt

class MapCanvasOverlay(
    private var points: List<NetworkPoint> = emptyList(),
    private val onPointClick: (NetworkPoint) -> Unit
) : Overlay() {

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val clusterPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = 24f
    }

    private val clusterTextPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = 18f
        color = android.graphics.Color.WHITE
    }

    private val screenPoint = Point()
    private val clickTolerance = 30f

    fun updatePoints(newPoints: List<NetworkPoint>) {
        points = newPoints
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return

        val projection = mapView.projection
        val viewBounds = projection.boundingBox ?: return

        val visiblePoints = points.filter { point ->
            isPointInBounds(point, viewBounds)
        }

        for (point in visiblePoints) {
            val geoPoint = GeoPoint(point.displayLatitude, point.displayLongitude)
            projection.toPixels(geoPoint, screenPoint)

            if (screenPoint.x >= 0 && screenPoint.x <= mapView.width &&
                screenPoint.y >= 0 && screenPoint.y <= mapView.height) {

                if (point.bssidDecimal == -1L) {
                    drawCluster(canvas, screenPoint, point)
                } else {
                    drawPoint(canvas, screenPoint, point)
                }
            }
        }
    }

    private fun drawPoint(canvas: Canvas, screenPoint: Point, point: NetworkPoint) {
        paint.color = point.color
        canvas.drawCircle(
            screenPoint.x.toFloat(),
            screenPoint.y.toFloat(),
            12f,
            paint
        )
    }

    private fun drawCluster(canvas: Canvas, screenPoint: Point, point: NetworkPoint) {
        val countText = point.essid?.substringBetween("(", " points)") ?: "0"
        val count = countText.toIntOrNull() ?: 0

        val isMultiPointCluster = count > 1

        val radius = if (isMultiPointCluster) {
            when {
                count >= 100000 -> 120f
                count >= 50000 -> 112f
                count >= 20000 -> 105f
                count >= 10000 -> 97f
                count >= 5000 -> 90f
                count >= 2000 -> 82f
                count >= 1000 -> 75f
                count >= 500 -> 67f
                count >= 200 -> 60f
                count >= 100 -> 52f
                count >= 50 -> 45f
                count >= 20 -> 37f
                count >= 10 -> 30f
                count >= 5 -> 27f
                else -> 22f
            }
        } else {
            12f
        }

        val textSize = if (isMultiPointCluster) {
            when {
                count >= 10000 -> 33f
                count >= 1000 -> 30f
                count >= 100 -> 27f
                count >= 10 -> 24f
                else -> 21f
            }
        } else {
            12f
        }

        clusterPaint.color = point.color
        canvas.drawCircle(
            screenPoint.x.toFloat(),
            screenPoint.y.toFloat(),
            radius,
            clusterPaint
        )

        clusterTextPaint.textSize = textSize
        val displayText = if (isMultiPointCluster) {
            when {
                count >= 1000000 -> "${count/1000000}M"
                count >= 1000 -> "${count/1000}k"
                else -> countText
            }
        } else {
            "1"
        }

        canvas.drawText(
            displayText,
            screenPoint.x.toFloat(),
            screenPoint.y + textSize/3,
            clusterTextPaint
        )
    }

    private fun isPointInBounds(point: NetworkPoint, bounds: org.osmdroid.util.BoundingBox): Boolean {
        return point.displayLatitude >= bounds.latSouth &&
                point.displayLatitude <= bounds.latNorth &&
                point.displayLongitude >= bounds.lonWest &&
                point.displayLongitude <= bounds.lonEast
    }

    override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
        val projection = mapView.projection
        val touchPoint = Point(e.x.toInt(), e.y.toInt())

        points.forEach { point ->
            val geoPoint = GeoPoint(point.displayLatitude, point.displayLongitude)
            projection.toPixels(geoPoint, screenPoint)

            val distance = sqrt(
                (touchPoint.x - screenPoint.x).toDouble().pow(2.0) +
                        (touchPoint.y - screenPoint.y).toDouble().pow(2.0)
            ).toFloat()

            if (distance <= clickTolerance) {
                onPointClick(point)
                return true
            }
        }

        return false
    }

    private fun String.substringBetween(start: String, end: String): String {
        val startIndex = this.indexOf(start) + start.length
        val endIndex = this.indexOf(end)
        return if (startIndex != -1 && endIndex != -1) {
            this.substring(startIndex, endIndex)
        } else {
            "0"
        }
    }
}