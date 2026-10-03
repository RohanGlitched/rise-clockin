package app.rise.clockin.ui.screens

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.rise.clockin.data.Mission
import app.rise.clockin.ui.theme.Rise
import app.rise.clockin.ui.theme.RiseType
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Small line drawing for each mission. */
@Composable
fun MissionGlyph(m: Mission, modifier: Modifier = Modifier, color: Color = Rise.Sun) {
    Canvas(modifier) {
        val w = size.width; val c = Offset(w / 2, size.height / 2); val s = w / 52f
        when (m) {
            Mission.Light -> {
                drawCircle(color, 9 * s, c)
                for (k in 0 until 8) {
                    val a = k * PI.toFloat() / 4
                    drawLine(color, Offset(c.x + cos(a) * 15 * s, c.y + sin(a) * 15 * s), Offset(c.x + cos(a) * 22 * s, c.y + sin(a) * 22 * s), 3.2f * s, StrokeCap.Round)
                }
            }
            Mission.Walk -> {
                fun foot(x: Float, y: Float) {
                    drawOval(color, Offset(x, y), Size(9 * s, 15 * s))
                    drawCircle(color, 3 * s, Offset(x + 4.5f * s, y - 4.5f * s))
                }
                foot(12 * s, 22 * s); foot(29 * s, 10 * s)
            }
            Mission.Spot -> {
                val st = Stroke(3.2f * s, cap = StrokeCap.Round)
                val l = 12 * s; val o = 8 * s; val e = w - o
                listOf(Offset(o, o) to Offset(1f, 1f), Offset(e, o) to Offset(-1f, 1f), Offset(o, e) to Offset(1f, -1f), Offset(e, e) to Offset(-1f, -1f)).forEach { (p, d) ->
                    drawLine(color, p, Offset(p.x + d.x * l, p.y), st.width, StrokeCap.Round)
                    drawLine(color, p, Offset(p.x, p.y + d.y * l), st.width, StrokeCap.Round)
                }
                drawRect(color, Offset(18 * s, 18 * s), Size(7 * s, 7 * s)); drawRect(color, Offset(27 * s, 27 * s), Size(7 * s, 7 * s))
                drawRect(color, Offset(27 * s, 18 * s), Size(4 * s, 4 * s)); drawRect(color, Offset(18 * s, 29 * s), Size(4 * s, 4 * s))
            }
        }
    }
}

/** Progress ring with a label in the middle; shared by every mission. */
@Composable
private fun MissionRing(progress: Float, big: String, small: String, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(250), label = "p")
    Box(modifier.aspectRatio(1f).semantics { contentDescription = "$big $small" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.06f
            val inset = stroke
            drawCircle(Brush.radialGradient(listOf(Rise.Sun.copy(alpha = 0.25f * p + 0.05f), Color.Transparent)), size.minDimension / 2)
            drawArc(Rise.Ivory.copy(alpha = 0.14f), 0f, 360f, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(stroke))
            drawArc(Rise.Sun, -90f, 360f * p, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(big, style = RiseType.clockLarge, color = Rise.Ivory)
            Text(small, style = RiseType.small, color = Rise.Mist)
        }
    }
}

// ------------------------------------------------------------------ Find the light

/**
 * The phone's ambient light sensor must read daylight (500 lux) for three seconds.
 * A bedside lamp gives ~100 lux; open curtains or a lit bathroom easily pass.
 */
@Composable
fun LightMission(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var lux by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableFloatStateOf(0f) }
    val sensor = remember { context.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_LIGHT) }
    val done by rememberUpdatedState(onDone)
    if (sensor == null) { MissingSensor("This phone has no light sensor. Switch to another mission."); return }
    DisposableEffect(sensor) {
        val sm = context.getSystemService(SensorManager::class.java)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { lux = e.values[0] }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    LaunchedEffect(Unit) {
        while (held < 1f) {
            delay(100)
            held = if (lux >= TARGET_LUX) (held + 0.1f / 3f) else (held - 0.05f).coerceAtLeast(0f)
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        done()
    }
    // Brightness on a log scale so the meter moves as you walk towards a window.
    val level = (ln(1f + lux) / ln(1f + TARGET_LUX)).coerceIn(0f, 1f)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        MissionRing(if (lux >= TARGET_LUX) 0.5f + held / 2 else level / 2, "${lux.toInt()}", "lux, reach $TARGET_LUX", Modifier.fillMaxWidth(0.72f))
        Spacer(Modifier.height(18.dp))
        Text(
            when {
                held > 0f -> "Hold it there…"
                level > 0.6f -> "Brighter. Almost daylight."
                else -> "Open the curtains or turn on the big light."
            },
            style = RiseType.heading, color = Rise.Ivory,
        )
    }
}
private const val TARGET_LUX = 500

// ------------------------------------------------------------------ Walk it off

/** Counts 30 steps with the step detector, or the accelerometer when there isn't one. */
@Composable
fun WalkMission(onDone: () -> Unit, modifier: Modifier = Modifier, goal: Int = 30) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var steps by remember { mutableIntStateOf(0) }
    val done by rememberUpdatedState(onDone)
    DisposableEffect(Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val detector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var lastPeak = 0L
        var smooth = 9.8f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type == Sensor.TYPE_STEP_DETECTOR) { steps++; return }
                val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                smooth = smooth * 0.8f + g * 0.2f
                val now = System.currentTimeMillis()
                if (smooth > 11.2f && now - lastPeak > 330) { lastPeak = now; steps++ }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        val usingDetector = detector != null && context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (usingDetector) sm.registerListener(l, detector, SensorManager.SENSOR_DELAY_FASTEST)
        else accel?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sm.unregisterListener(l) }
    }
    LaunchedEffect(steps) {
        if (steps > 0 && steps < goal && steps % 5 == 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (steps >= goal) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); done() }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        MissionRing(steps / goal.toFloat(), "${steps.coerceAtMost(goal)}", "of $goal steps", Modifier.fillMaxWidth(0.72f))
        Spacer(Modifier.height(18.dp))
        Text(if (steps == 0) "Get up and walk. The phone counts." else "Keep going, ${goal - steps} to go.", style = RiseType.heading, color = Rise.Ivory)
    }
}

// ------------------------------------------------------------------ Scan your wake spot

/** On-device QR scan of the wake-spot code (ML Kit); nothing leaves the phone. */
@OptIn(ExperimentalGetImage::class)
@Composable
fun SpotMission(expected: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val done by rememberUpdatedState(onDone)
    var wrong by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val hasCamera = context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!hasCamera) { MissingSensor("Rise needs the camera to scan your wake spot. Allow it in You, or switch mission."); return }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth(0.8f).aspectRatio(1f).clip(RoundedCornerShape(28.dp)).border(3.dp, Rise.Sun, RoundedCornerShape(28.dp)),
        ) {
            AndroidView(
                factory = { ctx ->
                    val view = PreviewView(ctx)
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { proxy ->
                            val img = proxy.image
                            if (img == null || finished) { proxy.close(); return@setAnalyzer }
                            scanner.process(InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees))
                                .addOnSuccessListener { codes ->
                                    val values = codes.mapNotNull { it.rawValue }
                                    if (values.any { it == expected } && !finished) {
                                        finished = true
                                        ContextCompat.getMainExecutor(ctx).execute { haptic.performHapticFeedback(HapticFeedbackType.LongPress); done() }
                                    } else if (values.isNotEmpty()) wrong = true
                                }
                                .addOnCompleteListener { proxy.close() }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }, ContextCompat.getMainExecutor(ctx))
                    view
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(if (wrong) "That's a different code. Find your wake spot." else "Point the camera at your wake spot.", style = RiseType.heading, color = Rise.Ivory)
    }
}

@Composable
private fun MissingSensor(text: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Rise.Rose.copy(alpha = 0.15f)).height(120.dp).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = RiseType.body, color = Rise.Ivory)
    }
}

fun Context.hasSensor(type: Int) = getSystemService(SensorManager::class.java).getDefaultSensor(type) != null
