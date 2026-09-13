package com.lsd.wififrankenstein.ui.wifimap

import android.graphics.Canvas
import android.graphics.Color
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

private const val CLICK_TOLERANCE = 30f
private const val OVERLAP_CELL_PX = 12f
private const val CLUSTER_MIN_RADIUS = 18f
private const val CLUSTER_MAX_RADIUS = 48f
private const val CLUSTER_BORDER_COLOR = 0xFFFF2196FB.toInt()
private const val CROSS_DATA_COLOR = 0xFFFFC107.toInt()
private const val WPASEC_COLOR = 0xFF00E5FF.toInt()
private const val OPEN_COLOR = 0xFFE53935.toInt()

class EfficientCanvasOverlay(
    private var points: List<MapPoint> = emptyList(),
    private val onPointClick: (MapPoint) -> Unit
) : Overlay() {

    var markerRadius: Float = 18f
    var showLabels: Boolean = false

    /** Invoked when a tap hits more than one point (e.g. exact-coordinate duplicates). */
    var onMultiplePointsClick: ((List<MapPoint>) -> Unit)? = null

    /** When false, overlapping points are drawn individually (user opted out of merging). */
    var mergeOverlappingPoints: Boolean = true

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
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

    private val crossDataRingPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = CROSS_DATA_COLOR
    }

    private val wpasecRingPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = WPASEC_COLOR
    }

    private val openDotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = OPEN_COLOR
    }

    private val reusableGeoPoint = GeoPoint(0.0, 0.0)
    private val reusableScreenPoint = Point()
    private val textBounds = RectF()
    private val clusterRadiusCache = mutableMapOf<Int, Float>()
    private val clusterTextSizeCache = mutableMapOf<Int, Float>()

    private fun drawIndividualPoint(canvas: Canvas, screenPoint: Point, point: MapPoint, zoom: Double) {
        paint.color = point.color
        paint.alpha = 255

        canvas.drawCircle(
            screenPoint.x.toFloat(),
            screenPoint.y.toFloat(),
            markerRadius,
            paint
        )

        individualPointBorderPaint.strokeWidth = 2.5f
        canvas.drawCircle(
            screenPoint.x.toFloat(),
            screenPoint.y.toFloat(),
            markerRadius,
            individualPointBorderPaint
        )

        if (point.hasCrossData) {
            canvas.drawCircle(
                screenPoint.x.toFloat(),
                screenPoint.y.toFloat(),
                markerRadius + 4f,
                crossDataRingPaint
            )
        }
        if (point.wpasecKnown) {
            canvas.drawCircle(
                screenPoint.x.toFloat(),
                screenPoint.y.toFloat(),
                markerRadius + if (point.hasCrossData) 8f else 4f,
                wpasecRingPaint
            )
        }
        if (point.isOpen) {
            canvas.drawCircle(
                screenPoint.x.toFloat(),
                screenPoint.y.toFloat(),
                markerRadius * 0.45f,
                openDotPaint
            )
        }

        if (showLabels && !point.essid.isNullOrBlank() && zoom >= 15.0) {
            canvas.drawText(point.essid!!, screenPoint.x.toFloat(), screenPoint.y.toFloat() - markerRadius - 5f, textPaint)
        }
    }

    fun updatePoints(newPoints: List<MapPoint>) {
        points = newPoints
        clusterRadiusCache.clear()
        clusterTextSizeCache.clear()
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return

        try {
            val projection = mapView.projection
            val viewBounds = projection.boundingBox ?: return

            canvas.clipRect(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat())

            // Project visible points once.
            val visible = ArrayList<ProjectedPoint>(points.size)
            for (pt in points) {
                val lat = pt.latitude
                val lon = pt.longitude
                if (lat < viewBounds.latSouth || lat > viewBounds.latNorth ||
                    lon < viewBounds.lonWest || lon > viewBounds.lonEast
                ) continue

                reusableGeoPoint.latitude = lat
                reusableGeoPoint.longitude = lon
                projection.toPixels(reusableGeoPoint, reusableScreenPoint)
                visible.add(
                    ProjectedPoint(
                        pt,
                        reusableScreenPoint.x.toFloat(),
                        reusableScreenPoint.y.toFloat()
                    )
                )
            }

            // Merge points landing on (nearly) the same pixel so networks with the same
            // coordinates do not stack invisibly on top of each other.
            if (!mergeOverlappingPoints) {
                val exactGroups = LinkedHashMap<Long, MutableList<ProjectedPoint>>()
                for (p in visible) {
                    val key = (p.point.latitudeBits shl 32) or (p.point.longitudeBits and 0xFFFFFFFFL)
                    exactGroups.getOrPut(key) { ArrayList(1) }.add(p)
                }
                for (group in exactGroups.values) {
                    if (group.size == 1) {
                        val p = group[0]
                        val screenPoint = Point(p.x.toInt(), p.y.toInt())
                        if (p.point.isCluster) {
                            drawClusterMarker(canvas, screenPoint, p.point)
                        } else {
                            drawIndividualPoint(canvas, screenPoint, p.point, mapView.zoomLevelDouble)
                        }
                    } else {
                        group.forEachIndexed { index, p ->
                            val angle = (index.toDouble() / group.size) * 2 * Math.PI
                            val radius = 14.0
                            val jittered = Point(
                                (p.x + radius * Math.cos(angle)).toInt(),
                                (p.y + radius * Math.sin(angle)).toInt()
                            )
                            drawIndividualPoint(canvas, jittered, p.point, mapView.zoomLevelDouble)
                        }
                    }
                }
                return
            }

            val groups = LinkedHashMap<Long, MutableList<ProjectedPoint>>()
            for (p in visible) {
                val cellX = (p.x / OVERLAP_CELL_PX).toInt()
                val cellY = (p.y / OVERLAP_CELL_PX).toInt()
                val key = (cellX.toLong() shl 32) or (cellY.toLong() and 0xFFFFFFFFL)
                groups.getOrPut(key) { ArrayList(1) }.add(p)
            }

            for (group in groups.values) {
                if (group.size == 1) {
                    val p = group[0]
                    val screenPoint = Point(p.x.toInt(), p.y.toInt())
                    if (p.point.isCluster) {
                        drawClusterMarker(canvas, screenPoint, p.point)
                    } else {
                        drawIndividualPoint(canvas, screenPoint, p.point, mapView.zoomLevelDouble)
                    }
                } else {
                    drawMergedMarker(canvas, group)
                }
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during canvas rendering, clearing points")
            points = emptyList()
            Runtime.getRuntime().gc()
        } catch (e: Exception) {
            Log.e(TAG, "Error in canvas draw", e)
        }
    }

    private class ProjectedPoint(val point: MapPoint, val x: Float, val y: Float)

    /** Draws one bubble with the total count for a set of overlapping points. */
    private fun drawMergedMarker(canvas: Canvas, group: List<ProjectedPoint>) {
        var count = 0
        var hasCross = false
        var wpasec = false
        var open = false
        var sumX = 0f
        var sumY = 0f
        for (p in group) {
            count += p.point.clusterCount.coerceAtLeast(1)
            hasCross = hasCross || p.point.hasCrossData
            wpasec = wpasec || p.point.wpasecKnown
            open = open || p.point.isOpen
            sumX += p.x
            sumY += p.y
        }
        val merged = group[0].point.copy(
            clusterCount = count,
            isCluster = true,
            hasCrossData = hasCross,
            wpasecKnown = wpasec,
            isOpen = open
        )
        drawClusterMarker(
            canvas,
            Point((sumX / group.size).toInt(), (sumY / group.size).toInt()),
            merged
        )
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
        clusterBorderPaint.color = when {
            point.wpasecKnown -> WPASEC_COLOR
            point.hasCrossData -> CROSS_DATA_COLOR
            point.isOpen -> OPEN_COLOR
            else -> CLUSTER_BORDER_COLOR
        }
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
            min(CLUSTER_MAX_RADIUS, markerRadius + 27f * sqrt(ratio))
        }
    }

    private fun getClusterTextSize(count: Int, radius: Float): Float {
        return clusterTextSizeCache.getOrPut(count) {
            max(14f, min(28f, radius * 0.9f))
        }
    }

    private fun getPointsAtScreenPosition(screenX: Int, screenY: Int, mapView: MapView): List<MapPoint> {
        if (points.isEmpty()) return emptyList()

        val projection = mapView.projection
        val bounds = projection.boundingBox ?: return emptyList()

        val hits = ArrayList<Pair<MapPoint, Double>>()
        val tempGeoPoint = GeoPoint(0.0, 0.0)
        val tempScreenPoint = Point()

        for (point in points) {
            val lat = point.latitude
            val lon = point.longitude
            if (lat < bounds.latSouth || lat > bounds.latNorth ||
                lon < bounds.lonWest || lon > bounds.lonEast
            ) continue

            tempGeoPoint.latitude = lat
            tempGeoPoint.longitude = lon
            projection.toPixels(tempGeoPoint, tempScreenPoint)

            val distance = sqrt(
                (tempScreenPoint.x.toDouble() - screenX.toDouble()).pow(2.0) +
                        (tempScreenPoint.y.toDouble() - screenY.toDouble()).pow(2.0)
            )

            if (distance < CLICK_TOLERANCE) hits.add(point to distance)
        }

        return hits.sortedBy { it.second }.map { it.first }
    }

    override fun onSingleTapConfirmed(event: MotionEvent, mapView: MapView): Boolean {
        val candidates = getPointsAtScreenPosition(event.x.toInt(), event.y.toInt(), mapView)
        if (candidates.isEmpty()) return false
        if (candidates.size == 1) {
            onPointClick(candidates[0])
        } else {
            // Several networks share the tap position: let the caller pick one.
            onMultiplePointsClick?.invoke(candidates) ?: onPointClick(candidates[0])
        }
        return true
    }
}
