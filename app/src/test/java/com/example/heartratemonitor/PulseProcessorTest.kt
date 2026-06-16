package com.example.heartratemonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class PulseProcessorTest {
    private fun feedSignal(
        processor: PulseProcessor,
        targetBpm: Double,
        seconds: Double = 12.0,
        fps: Double = 30.0,
        noise: Double = 0.0
    ): Double {
        val frames = (seconds * fps).toInt()
        val nanoPerFrame = (1_000_000_000.0 / fps).toLong()
        val freqHz = targetBpm / 60.0
        val rnd = Random(42)
        var bpm = 0.0
        for (i in 0 until frames) {
            val t = i / fps
            val baseline = 180.0 + 5.0 * sin(2 * PI * 0.05 * t)
            val pulse = 8.0 * sin(2 * PI * freqHz * t)
            val n = if (noise > 0) (rnd.nextDouble() - 0.5) * noise else 0.0
            val value = baseline + pulse + n
            bpm = processor.addSample(i.toLong() * nanoPerFrame, value)
        }
        return bpm
    }
    @Test
    fun detects_60_bpm() {
        val p = PulseProcessor()
        val bpm = feedSignal(p, targetBpm = 60.0)
        assertEquals("It should be ~60 BPM", 60.0, bpm, 5.0)
    }

    @Test
    fun detects_75_bpm() {
        val p = PulseProcessor()
        val bpm = feedSignal(p, targetBpm = 75.0)
        assertEquals("It should be ~75 BPM", 75.0, bpm, 5.0)
    }

    @Test
    fun detects_100_bpm() {
        val p = PulseProcessor()
        val bpm = feedSignal(p, targetBpm = 100.0)
        assertEquals("It shold be ~100 BPM", 100.0, bpm, 6.0)
    }

    @Test
    fun different_inputs_give_different_outputs() {
        // Daca "scotea numere din burta", astea ar fi egale. Trebuie sa difere clar.
        val low = feedSignal(PulseProcessor(), targetBpm = 55.0)
        val high = feedSignal(PulseProcessor(), targetBpm = 110.0)
        assertTrue("55 si 110 BPM should give different results (low=$low high=$high)",
            high - low > 30.0)
    }

    @Test
    fun survives_moderate_noise() {
        val p = PulseProcessor()
        val bpm = feedSignal(p, targetBpm = 80.0, noise = 3.0)
        assertEquals("Whit moderate noise ~80 BPM", 80.0, bpm, 8.0)
    }
}