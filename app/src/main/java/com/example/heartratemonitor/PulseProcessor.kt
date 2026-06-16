package com.example.heartratemonitor

import kotlin.math.sqrt

/**
 * Transforma fluxul de valori brute (media canalului ROSU per frame) intr-un puls
 * in batai pe minut (BPM), folosind o metoda PPG standard:
 *
 *  1. Istoric de esantioane (valoare + timp real de sistem, in nanosecunde).
 *  2. Detrend: scadem media mobila lunga -> ramane doar pulsatia (eliminam deriva).
 *  3. Detectare varfuri: un varf local care depaseste un prag bazat pe deviatia
 *     standard a semnalului (adaptiv la amplitudinea reala), cu interval minim
 *     intre batai (refractar) ca sa nu numaram acelasi puls de doua ori.
 *  4. BPM = mediana intervalelor dintre varfuri (mediana e robusta la rateuri).
 *
 * Toate metodele se apeleaza pe acelasi thread (analizatorul CameraX).
 */
class PulseProcessor {

    private data class Sample(val timeNanos: Long, val raw: Double, val detrended: Double)

    private val samples = ArrayDeque<Sample>()
    private val peakTimes = ArrayDeque<Long>()

    private var bpm: Double = 0.0

    /** Semnalul filtrat (detrended) pentru graficul live. */
    val waveform: List<Float>
        get() = samples.map { it.detrended.toFloat() }

    fun addSample(timeNanos: Long, rawRed: Double): Double {
        // 2. Detrend cu media mobila lunga.
        val baselineCount = minOf(samples.size, BASELINE_WINDOW)
        val baseline = if (baselineCount == 0) rawRed
            else samples.takeLast(baselineCount).sumOf { it.raw } / baselineCount
        val detrended = rawRed - baseline

        samples.addLast(Sample(timeNanos, rawRed, detrended))

        // Pastram doar fereastra recenta.
        val cutoff = timeNanos - WINDOW_NANOS
        while (samples.isNotEmpty() && samples.first().timeNanos < cutoff) {
            samples.removeFirst()
        }
        while (peakTimes.isNotEmpty() && peakTimes.first() < cutoff) {
            peakTimes.removeFirst()
        }

        // 3. Detectare varf pe penultimul esantion (avem nevoie de un vecin de fiecare parte).
        detectPeak()

        // 4. Recalculam BPM.
        bpm = computeBpm()
        return bpm
    }

    private fun detectPeak() {
        val n = samples.size
        if (n < 3) return

        val values = samples.map { it.detrended }
        val prev = values[n - 3]
        val mid = values[n - 2]
        val next = values[n - 1]

        // Prag adaptiv: varful trebuie sa fie peste o fractiune din deviatia standard.
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        val std = sqrt(variance)
        val threshold = std * PEAK_STD_FACTOR

        // Varf local strict + suficient de mare fata de zgomot.
        val isLocalMax = mid > prev && mid > next
        if (!isLocalMax || mid < threshold) return

        val peakTime = samples[n - 2].timeNanos

        // Refractar: ignoram varfuri prea apropiate (peste 220 BPM).
        val last = peakTimes.lastOrNull()
        if (last == null || (peakTime - last) > MIN_PEAK_INTERVAL_NANOS) {
            peakTimes.addLast(peakTime)
        }
    }

    private fun computeBpm(): Double {
        if (peakTimes.size < MIN_PEAKS) return bpm  // pastram ultima valoare valida

        val times = peakTimes.toList()
        val intervals = ArrayList<Double>(times.size - 1)
        for (i in 1 until times.size) {
            intervals.add((times[i] - times[i - 1]) / 1_000_000_000.0)
        }

        // Doar intervale fiziologic plauzibile (30..220 BPM).
        val valid = intervals.filter { it in MIN_INTERVAL_SEC..MAX_INTERVAL_SEC }.sorted()
        if (valid.size < MIN_PEAKS - 1) return bpm

        // Mediana intervalelor -> robusta la o bataie ratata sau una falsa.
        val median = valid[valid.size / 2]
        return 60.0 / median
    }

    /** Cat de sigura e masuratoarea (0..1): cate batai consistente am prins. */
    val confidence: Float
        get() = (peakTimes.size.toFloat() / CONFIDENT_PEAKS).coerceIn(0f, 1f)

    fun reset() {
        samples.clear()
        peakTimes.clear()
        bpm = 0.0
    }

    companion object {
        private const val WINDOW_NANOS = 8_000_000_000L     // 8 secunde de istoric
        private const val BASELINE_WINDOW = 14              // ~0.5s: high-pass care taie unda lenta de respiratie
        private const val PEAK_STD_FACTOR = 0.45            // prag echilibrat: prinde toate bataile fara zgomot

        private const val MIN_PEAK_INTERVAL_NANOS = 333_000_000L // < 180 BPM (taie dublarile de varfuri)
        private const val MIN_INTERVAL_SEC = 60.0 / 180.0
        private const val MAX_INTERVAL_SEC = 60.0 / 30.0

        private const val MIN_PEAKS = 3                     // minim 3 varfuri ca sa dam o cifra
        private const val CONFIDENT_PEAKS = 6f
    }
}
