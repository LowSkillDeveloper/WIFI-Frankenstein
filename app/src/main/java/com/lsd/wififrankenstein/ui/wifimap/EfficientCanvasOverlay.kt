package com.lsd.wififrankenstein.ui.wifimap

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.view.MotionEvent
import com.lsd.wififrankenstein.util.Log
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private const val TAG = "EfficientCanvas"

private const val POINT_RADIUS = 18f
private const val CLICK_TOLERANCE = 30f
private const val CLUSTER_MIN_RADIUS = 18f
private const val CLUSTER_MAX_RADIUS = 48f
private const val CLUSTER_BORDER_COLOR = 0xFFFF2196FB.toInt()

class EfficientCanvasOverlay(
    private var points: List<MapPoint> = emptyList(),
    private val onPointClick: (MapPoint) -> Unit
) : Overlay() {

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val clusterBackgroundPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val clusterBorderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = android.graphics.Color.WHITE
    }

    private val clusterTextPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        color = android.graphics.Color.WHITE
    }

    private val individualPointBorderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = android.graphics.Color.WHITE
    }

    private val reusableGeoPoint = GeoPoint(0.0, 0.0)
    private val reusableScreenPoint = Point()
    private val textBounds = RectF()
    private val clusterRadiusCache = mutableMapOf<Int, Float>()
    private val clusterTextSizeCache = mutableMapOf<Int, Float>()

    private fun drawIndividualPoint(canvas: Canvas, screenPoint: Point, point: MapPoint) {
        paint.color = point.color
        paint.alpha = 255

        canvas.drawCircle(
            screenPoint.x.toFloat(),
            screenPoint.y.toFloat(),
            POINT_RADIUS,
            paint
        )

        individualPointBorderPaint.strokeWidth = 2.5f
        canvas.drawCircle(
            screenPoint.x.toFloat(),
            screenPoint.y.toFloat(),
            POINT_RADIUS,
            individualPointBorderPaint
        )
    }

    fun updatePoints(newPoints: List<MapPoint>) {
        points = newPoints
        clusterRadiusCache.clear()
        clusterTextSizeCache.clear()
        Log.d(TAG, "[Overlay] Loaded points: ${newPoints.size}")
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return

        try {
            val projection = mapView.projection
            val viewBounds = projection.boundingBox ?: return

            canvas.clipRect(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat())

            var clusterCount = 0
            var pointCount = 0

            for (pt in points) {
                val lat = pt.latitude
                val lon = pt.longitude
                if (lat < viewBounds.latSouth || lat > viewBounds.latNorth ||
                    lon < viewBounds.lonWest || lon > viewBounds.lonEast
                ) continue

                reusableGeoPoint.latitude = lat
                reusableGeoPoint.longitude = lon
                projection.toPixels(reusableGeoPoint, reusableScreenPoint)

                if (pt.isCluster) {
                    drawClusterMarker(canvas, reusableScreenPoint, pt)
                    clusterCount++
                } else {
                    drawIndividualPoint(canvas, reusableScreenPoint, pt)
                    pointCount++
                }
            }

            Log.d(TAG, "[Render] Drawing ${clusterCount} clusters + ${pointCount} points")
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during canvas rendering, clearing points")
            points = emptyList()
            Runtime.getRuntime().gc()
        } catch (e: Exception) {
            Log.e(TAG, "Error in canvas draw", e)
        }
    }

    private fun drawClusterMarker(canvas: Canvas, screenPoint: Point, point: MapPoint) {
        val count = point.clusterCount
        val radius = getClusterRadius(count)
        val color = point.color

        clusterBackgroundPaint.color = color

        canvas.drawCircle(
            screenPoint.x.toFloat(),
            screenPoint.y.toFloat(),
            radius,
            clusterBackgroundPaint
        )

        clusterBorderPaint.strokeWidth = 2f
        clusterBorderPaint.color = CLUSTER_BORDER_COLOR
        canvas.drawCircle(
            screenPoint.x.toFloat(),
            screenPoint.y.toFloat(),
            radius,
            clusterBorderPaint
        )

        clusterTextPaint.textSize = getClusterTextSize(count, radius)
        val text = if (count > 0) count.toString() else "Cluster"

        val metrics = clusterTextPaint.fontMetrics
        val textY = screenPoint.y.toFloat() - metrics.top / 2.0f

        canvas.drawText(text, screenPoint.x.toFloat(), textY, clusterTextPaint)
    }

    private fun getClusterRadius(count: Int): Float {
        return clusterRadiusCache.getOrPut(count) {
            val maxCount = 500f
            val ratio = count.toFloat() / maxCount
            min(CLUSTER_MAX_RADIUS, CLUSTER_MIN_RADIUS + 27f * sqrt(ratio))
        }
    }

    private fun getClusterTextSize(count: Int, radius: Float): Float {
        return clusterTextSizeCache.getOrPut(count) {
            max(14f, min(28f, radius * 0.9f))
        }
    }

    private fun getPointAtScreenPosition(screenX: Int, screenY: Int, mapView: MapView): MapPoint? {
        if (points.isEmpty()) return null

        val projection = mapView.projection

        val bounds = projection.boundingBox ?: return null
        val visiblePoints = points.filter { pt ->
            val lat = pt.latitude
            val lon = pt.longitude
            lat >= bounds.latSouth && lat <= bounds.latNorth &&
                    lon >= bounds.lonWest && lon <= bounds.lonEast
        }

        if (visiblePoints.isEmpty()) return null

        val tempGeoPoint = GeoPoint(0.0, 0.0)
        val tempScreenPoint = Point()

        for (point in visiblePoints) {
            tempGeoPoint.latitude = point.latitude
            tempGeoPoint.longitude = point.longitude
            projection.toPixels(tempGeoPoint, tempScreenPoint)

            val distance = sqrt(
                (tempScreenPoint.x.toDouble() - screenX.toDouble()).pow(2.0) +
                        (tempScreenPoint.y.toDouble() - screenY.toDouble()).pow(2.0)
            )

            if (distance < CLICK_TOLERANCE) {
                return point
            }
        }

        return null
    }

    override fun onSingleTapConfirmed(event: MotionEvent, mapView: MapView): Boolean {
        val clickedPoint = getPointAtScreenPosition(event.x.toInt(), event.y.toInt(), mapView)
        if (clickedPoint != null) {
            onPointClick(clickedPoint)
            return true
        }
        return false
    }
}
