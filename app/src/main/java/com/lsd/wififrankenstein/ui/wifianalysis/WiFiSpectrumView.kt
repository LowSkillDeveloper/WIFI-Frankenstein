package com.lsd.wififrankenstein.ui.wifianalysis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.wifi.ScanResult
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.lsd.wififrankenstein.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class WiFiSpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var networkInfoList = listOf<NetworkChannelInfo>()
    private var excludedBssids = setOf<String>()
    private var currentBand = FrequencyBand.GHZ_2_4

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textRenderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val canvasPadding = 60f
    private val textSizePixels = 32f
    private val spectrumStrokeWidth = 4f

    private val channelColors = listOf(
        ContextCompat.getColor(context, R.color.spectrum_color_1),
        ContextCompat.getColor(context, R.color.spectrum_color_2),
        ContextCompat.getColor(context, R.color.spectrum_color_3),
        ContextCompat.getColor(context, R.color.spectrum_color_4),
        ContextCompat.getColor(context, R.color.spectrum_color_5)
    )

    init {
        setupPaintObjects()
    }

    private fun setupPaintObjects() {
        val textColor = ContextCompat.getColor(context, R.color.text_primary)
        val gridColor = ContextCompat.getColor(context, R.color.grid_color)

        gridLinePaint.apply {
            color = gridColor
            strokeWidth = 1f
            style = Paint.Style.STROKE
            alpha = 128
        }

        textRenderPaint.apply {
            color = textColor
            textSize = textSizePixels
            textAlign = Paint.Align.CENTER
        }

        spectrumPaint.apply {
            strokeWidth = spectrumStrokeWidth
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
    }

    fun updateSpectrumData(networks: List<ScanResult>, excludedBssids: Set<String>, band: FrequencyBand) {
        this.networkInfoList = networks
            .filter { !excludedBssids.contains(it.BSSID) }
            .filter { NetworkFrequencyAnalyzer.determineFrequencyBand(it.frequency) == band }
            .mapNotNull { result ->
                val channel = NetworkFrequencyAnalyzer.determineChannelFromFrequency(result.frequency, band)
                if (channel != -1) {
                    val bandwidth = ChannelBandwidth.fromScanResult(result)
                    val startFreq = result.frequency - bandwidth.spreadRadius
                    val endFreq = result.frequency + bandwidth.spreadRadius

                    NetworkChannelInfo(
                        scanResult = result,
                        channel = channel,
                        frequency = result.frequency,
                        channelWidth = bandwidth,
                        startFrequency = startFreq,
                        endFrequency = endFreq,
                        band = band
                    )
                } else null
            }

        this.excludedBssids = excludedBssids
        this.currentBand = band
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (networkInfoList.isEmpty()) {
            drawEmptyMessage(canvas)
            return
        }

        val drawableWidth = width - 2 * canvasPadding
        val drawableHeight = height - 2 * canvasPadding

        drawGridLines(canvas, drawableWidth, drawableHeight)
        drawAxes(canvas, drawableWidth, drawableHeight)
        drawSpectrumData(canvas, drawableWidth, drawableHeight)
        drawAxisLabels(canvas, drawableWidth, drawableHeight)
    }

    private fun drawEmptyMessage(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f

        textRenderPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(context.getString(R.string.no_wifi_data_available), centerX, centerY, textRenderPaint)
    }

    private fun drawGridLines(canvas: Canvas, drawableWidth: Float, drawableHeight: Float) {
        val channelRange = getChannelRangeForBand()
        val minChannel = channelRange.minOrNull() ?: 1
        val maxChannel = channelRange.maxOrNull() ?: 11
        val channelSpan = maxChannel - minChannel

        for (channel in channelRange) {
            val x = canvasPadding + (channel - minChannel).toFloat() / channelSpan * drawableWidth
            canvas.drawLine(x, canvasPadding, x, canvasPadding + drawableHeight, gridLinePaint)
        }

        for (i in 0..5) {
            val y = canvasPadding + i * drawableHeight / 5
            canvas.drawLine(canvasPadding, y, canvasPadding + drawableWidth, y, gridLinePaint)
        }
    }

    private fun drawAxes(canvas: Canvas, drawableWidth: Float, drawableHeight: Float) {
        backgroundPaint.color = ContextCompat.getColor(context, R.color.text_primary)
        backgroundPaint.strokeWidth = 2f

        canvas.drawLine(canvasPadding, canvasPadding + drawableHeight, canvasPadding + drawableWidth, canvasPadding + drawableHeight, backgroundPaint)
        canvas.drawLine(canvasPadding, canvasPadding, canvasPadding, canvasPadding + drawableHeight, backgroundPaint)
    }

    private fun drawSpectrumData(canvas: Canvas, drawableWidth: Float, drawableHeight: Float) {
        val channelRange = getChannelRangeForBand()
        val minChannel = channelRange.minOrNull() ?: 1
        val maxChannel = channelRange.maxOrNull() ?: 11
        val channelSpan = maxChannel - minChannel

        val maxSignalLevel = 0
        val minSignalLevel = -100
        val signalRange = maxSignalLevel - minSignalLevel
        val minVisibleHeight = drawableHeight * 0.05f

        networkInfoList.forEach { networkInfo ->
            val channel = networkInfo.channel
            if (channel in minChannel..maxChannel) {
                val centerX = canvasPadding + (channel - minChannel).toFloat() / channelSpan * drawableWidth
                val signalLevel = max(minSignalLevel, min(maxSignalLevel, networkInfo.scanResult.level))
                val normalizedSignal = (signalLevel - minSignalLevel).toFloat() / signalRange
                val signalHeight = max(minVisibleHeight, drawableHeight * normalizedSignal)
                val peakY = canvasPadding + drawableHeight - signalHeight

                renderNetworkSpectrum(canvas, centerX, peakY, networkInfo, drawableWidth, drawableHeight)
            }
        }
    }

    private fun renderNetworkSpectrum(
        canvas: Canvas,
        centerX: Float,
        peakY: Float,
        networkInfo: NetworkChannelInfo,
        drawableWidth: Float,
        drawableHeight: Float
    ) {
        val baseY = canvasPadding + drawableHeight
        val bandwidth = networkInfo.channelWidth

        val channelRange = getChannelRangeForBand()
        val minChannel = channelRange.minOrNull() ?: 1
        val maxChannel = channelRange.maxOrNull() ?: 11
        val channelSpan = maxChannel - minChannel

        val widthSpread = when (currentBand) {
            FrequencyBand.GHZ_2_4 -> {
                val channelsSpread = when (bandwidth) {
                    ChannelBandwidth.WIDTH_20 -> 1.0f
                    ChannelBandwidth.WIDTH_40 -> 4.0f
                    else -> 1.0f
                }
                (channelsSpread / channelSpan) * drawableWidth
            }
            FrequencyBand.GHZ_5, FrequencyBand.GHZ_6 -> {
                val channelsSpread = when (bandwidth) {
                    ChannelBandwidth.WIDTH_20 -> 1.0f
                    ChannelBandwidth.WIDTH_40 -> 2.0f
                    ChannelBandwidth.WIDTH_80 -> 4.0f
                    ChannelBandwidth.WIDTH_160 -> 8.0f
                    ChannelBandwidth.WIDTH_320 -> 16.0f
                }
                (channelsSpread / channelRange.size) * drawableWidth
            }
        }

        val leftX = max(canvasPadding, centerX - widthSpread / 2)
        val rightX = min(canvasPadding + drawableWidth, centerX + widthSpread / 2)

        val colorIndex = abs(networkInfo.scanResult.BSSID.hashCode()) % channelColors.size
        val color = channelColors[colorIndex]

        spectrumPaint.color = color
        spectrumPaint.style = Paint.Style.FILL
        spectrumPaint.alpha = 180

        val spectrumPath = Path()
        spectrumPath.moveTo(leftX, baseY)
        spectrumPath.quadTo(centerX, peakY, rightX, baseY)
        spectrumPath.close()

        canvas.drawPath(spectrumPath, spectrumPaint)

        spectrumPaint.style = Paint.Style.STROKE
        spectrumPaint.alpha = 255
        canvas.drawPath(spectrumPath, spectrumPaint)

        if (rightX - leftX > 60 && networkInfo.scanResult.SSID.isNotBlank()) {
            textRenderPaint.textSize = 24f
            textRenderPaint.color = color
            val ssidText = networkInfo.scanResult.SSID.take(8)
            canvas.drawText(ssidText, centerX, peakY - 10, textRenderPaint)
            textRenderPaint.textSize = textSizePixels
        }
    }

    private fun getChannelRangeForBand(): List<Int> {
        return when (currentBand) {
            FrequencyBand.GHZ_2_4 -> (1..14).toList()
            FrequencyBand.GHZ_5 -> listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165)
            FrequencyBand.GHZ_6 -> (1..29).toList()
        }
    }

    private fun drawAxisLabels(canvas: Canvas, drawableWidth: Float, drawableHeight: Float) {
        val channelRange = getChannelRangeForBand()
        val minChannel = channelRange.minOrNull() ?: 1
        val maxChannel = channelRange.maxOrNull() ?: 11
        val channelSpan = maxChannel - minChannel

        textRenderPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        textRenderPaint.textAlign = Paint.Align.CENTER
        textRenderPaint.textSize = 28f

        val stepSize = max(1, channelSpan / 10)
        for (i in channelRange.indices step stepSize) {
            val channel = channelRange[i]
            val x = canvasPadding + (channel - minChannel).toFloat() / channelSpan * drawableWidth
            canvas.drawText(channel.toString(), x, canvasPadding + drawableHeight + 40f, textRenderPaint)
        }

        textRenderPaint.textAlign = Paint.Align.RIGHT
        val signalLevels = listOf(0, -20, -40, -60, -80, -100)
        for (i in signalLevels.indices) {
            val signalValue = signalLevels[i]
            val y = canvasPadding + (i * drawableHeight / (signalLevels.size - 1)) + 10f
            canvas.drawText("${signalValue}dBm", canvasPadding - 10f, y, textRenderPaint)
        }

        textRenderPaint.textSize = textSizePixels
    }
}