package com.example.heartratemonitor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Starea masuratorii, observabila din Compose. */
data class HeartRateUiState(
    val bpm: Int = 0,
    val fingerDetected: Boolean = false,
    val confidence: Float = 0f,
    val waveform: List<Float> = emptyList()
)

class HeartRateViewModel : ViewModel() {

    private val processor = PulseProcessor()

    var uiState by mutableStateOf(HeartRateUiState())
        private set

    // Cate frame-uri consecutive fara deget, ca sa resetam (degetul a fost ridicat).
    private var framesWithoutFinger = 0

    /** Apelata din analizatorul CameraX pentru fiecare frame. */
    fun onFrame(timeNanos: Long, value: Double, fingerDetected: Boolean) {
        if (!fingerDetected) {
            framesWithoutFinger++
            if (framesWithoutFinger > FRAMES_BEFORE_RESET) {
                processor.reset()
                uiState = HeartRateUiState(fingerDetected = false)
            } else {
                uiState = uiState.copy(fingerDetected = false)
            }
            return
        }

        framesWithoutFinger = 0
        val bpm = processor.addSample(timeNanos, value)

        uiState = HeartRateUiState(
            bpm = bpm.toInt(),
            fingerDetected = true,
            confidence = processor.confidence,
            waveform = processor.waveform
        )
    }

    companion object {
        private const val FRAMES_BEFORE_RESET = 15
    }
}
