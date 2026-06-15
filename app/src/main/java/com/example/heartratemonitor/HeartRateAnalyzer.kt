package com.example.heartratemonitor

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Analizeaza fiecare frame de la camera si extrage intensitatea medie a "rosului".
 *
 * Principiul (fotopletismografie / PPG): cu degetul lipit de camera si blitz,
 * sangele oxigenat care pulseaza la fiecare bataie a inimii modifica usor cat de
 * multa lumina rosie ajunge inapoi la senzor. Deci media canalului ROSU pe frame
 * "oscileaza" in ritmul pulsului.
 *
 * CameraX livreaza frame-uri YUV_420_888. Calculam media reala a canalului ROSU
 * convertind din YUV -> RGB (doar componenta R). Detectam degetul dupa faptul ca,
 * atunci cand un deget iluminat de blitz acopera camera, ROSUL domina puternic
 * fata de verde/albastru -> semnatura clara, robusta la luminozitatea generala.
 *
 * @param onMeasurement primeste (timestampNanos, mediaRosu, degetDetectat)
 */
class HeartRateAnalyzer(
    private val onMeasurement: (timestampNanos: Long, value: Double, fingerDetected: Boolean) -> Unit
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val (avgR, avgG, avgB) = computeAverageRgb(image)

            // Un deget iluminat de blitz arata aproape rosu pur: R mare, R mult peste G/B.
            val redDominates = avgR > avgG * RED_DOMINANCE && avgR > avgB * RED_DOMINANCE
            val brightEnough = avgR > MIN_RED
            val fingerDetected = redDominates && brightEnough

            // Folosim ceasul REAL al sistemului (nanosecunde), nu timestamp-ul camerei,
            // care pe unele telefoane nu e in nanosecunde corecte -> BPM aberant.
            val now = System.nanoTime()

            // Log temporar pentru calibrare. Vezi in Logcat: filtreaza dupa "PPG".
            Log.d("PPG", "R=%.1f G=%.1f B=%.1f finger=%b".format(avgR, avgG, avgB, fingerDetected))

            onMeasurement(now, avgR, fingerDetected)
        } finally {
            // OBLIGATORIU: altfel CameraX nu mai trimite frame-uri noi.
            image.close()
        }
    }

    /**
     * Media canalelor R, G, B pe un esantion de pixeli, convertind din YUV_420_888.
     * Esantionam (fiecare al N-lea pixel) pentru viteza.
     */
    private fun computeAverageRgb(image: ImageProxy): Triple<Double, Double, Double> {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer: ByteBuffer = yPlane.buffer
        val uBuffer: ByteBuffer = uPlane.buffer
        val vBuffer: ByteBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val width = image.width
        val height = image.height

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0L

        var row = 0
        while (row < height) {
            var col = 0
            while (col < width) {
                val yIndex = row * yRowStride + col * yPixelStride
                // U/V sunt subesantionate la jumatate de rezolutie (4:2:0).
                val uvRow = row / 2
                val uvCol = col / 2
                val uvIndex = uvRow * uvRowStride + uvCol * uvPixelStride

                if (yIndex < yBuffer.limit() && uvIndex < uBuffer.limit() && uvIndex < vBuffer.limit()) {
                    val y = (yBuffer.get(yIndex).toInt() and 0xFF)
                    val u = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                    val v = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128

                    // Conversie YUV -> RGB (BT.601), apoi limitam la 0..255.
                    val r = (y + 1.370705 * v).toInt().coerceIn(0, 255)
                    val g = (y - 0.337633 * u - 0.698001 * v).toInt().coerceIn(0, 255)
                    val b = (y + 1.732446 * u).toInt().coerceIn(0, 255)

                    sumR += r
                    sumG += g
                    sumB += b
                    count++
                }
                col += COLUMN_SAMPLE_STEP
            }
            row += ROW_SAMPLE_STEP
        }

        if (count == 0L) return Triple(0.0, 0.0, 0.0)
        return Triple(
            sumR.toDouble() / count,
            sumG.toDouble() / count,
            sumB.toDouble() / count
        )
    }

    companion object {
        // Pas de esantionare: luam fiecare al N-lea pixel pentru viteza.
        private const val ROW_SAMPLE_STEP = 6
        private const val COLUMN_SAMPLE_STEP = 6

        // Cat de mult trebuie sa domine rosul fata de verde/albastru ca sa fie "deget".
        private const val RED_DOMINANCE = 1.6
        // Rosul mediu minim (un deget bine iluminat de blitz este foarte rosu).
        private const val MIN_RED = 110.0
    }
}
