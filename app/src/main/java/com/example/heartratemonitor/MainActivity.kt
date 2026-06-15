package com.example.heartratemonitor

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.heartratemonitor.ui.theme.HeartRateMonitorTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HeartRateMonitorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HeartRateScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HeartRateScreen(modifier: Modifier = Modifier) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (cameraPermission.status.isGranted) {
            MeasurementContent()
        } else {
            PermissionRequest(onRequest = { cameraPermission.launchPermissionRequest() })
        }
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Monitor de puls",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Aplicatia foloseste camera si blitzul pentru a masura pulsul. " +
                "Acopera complet camera din spate cu varful degetului.",
            modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onBackground
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 24.dp)) {
            Text("Permite accesul la camera")
        }
    }
}

@Composable
private fun MeasurementContent() {
    val viewModel: HeartRateViewModel = viewModel()
    val state = viewModel.uiState

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Porneste camera + blitzul invizibil (analiza ruleaza in fundal).
        CameraController(onFrame = viewModel::onFrame)

        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (state.bpm > 0) "${state.bpm}" else "--",
                    fontSize = 88.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(text = "BPM", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
        }

        StatusText(state)

        WaveformGraph(
            waveform = state.waveform,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(top = 24.dp)
                .clip(RoundedCornerShape(16.dp))
        )
    }
}

@Composable
private fun StatusText(state: HeartRateUiState) {
    val (message, color) = when {
        !state.fingerDetected ->
            "Pune degetul pe camera (cu blitzul)" to MaterialTheme.colorScheme.error
        state.confidence < 1f ->
            "Se masoara... tine degetul nemiscat" to MaterialTheme.colorScheme.tertiary
        else ->
            "Masuratoare stabila" to MaterialTheme.colorScheme.primary
    }
    Text(text = message, color = color, modifier = Modifier.padding(top = 8.dp))
}

/** Deseneaza forma de unda a pulsului (semnalul filtrat). */
@Composable
private fun WaveformGraph(waveform: List<Float>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier) {
        // Fundal
        drawRect(color = bg)

        if (waveform.size < 2) return@Canvas

        val maxAbs = (waveform.maxOf { kotlin.math.abs(it) }).coerceAtLeast(0.001f)
        val midY = size.height / 2f
        val stepX = size.width / (waveform.size - 1)

        var prev = Offset(0f, midY - (waveform[0] / maxAbs) * (size.height / 2.2f))
        for (i in 1 until waveform.size) {
            val x = i * stepX
            val y = midY - (waveform[i] / maxAbs) * (size.height / 2.2f)
            val current = Offset(x, y)
            drawLine(
                color = lineColor,
                start = prev,
                end = current,
                strokeWidth = 4f
            )
            prev = current
        }
    }
}

/**
 * Leaga CameraX de ciclul de viata: porneste analiza frame-urilor si blitzul
 * (torch), apoi le inchide cand ecranul dispare.
 */
@Composable
private fun CameraController(
    onFrame: (timeNanos: Long, value: Double, fingerDetected: Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var boundProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            boundProvider = cameraProvider

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(
                        executor,
                        HeartRateAnalyzer { time, value, finger ->
                            onFrame(time, value, finger)
                        }
                    )
                }

            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                analysis
            )

            // Pornim blitzul daca dispozitivul are unul.
            if (camera.cameraInfo.hasFlashUnit()) {
                camera.cameraControl.enableTorch(true)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            boundProvider?.unbindAll()
            executor.shutdown()
        }
    }
}

@Composable
fun HeartRateScreenPreviewWrapper() {
    HeartRateMonitorTheme {
        PermissionRequest(onRequest = {})
    }
}