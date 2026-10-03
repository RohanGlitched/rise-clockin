package app.rise.clockin.ui.sky

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import app.rise.clockin.ui.theme.Rise
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The sky behind every screen. `dawn` runs from 0 (deep night) to 1 (sunrise);
 * the sun climbs from below the horizon as it grows. Rendered by an AGSL shader
 * on Android 13+, with a gradient fallback below that.
 */
private const val SKY_AGSL = """
uniform float2 res;
uniform float time;
uniform float dawn;
uniform float sunX;
uniform float horizon;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }

float noise(float2 p) {
    float2 i = floor(p); float2 f = fract(p);
    float a = hash(i), b = hash(i + float2(1, 0)), c = hash(i + float2(0, 1)), d = hash(i + float2(1, 1));
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

half4 main(float2 fc) {
    float2 uv = fc / res;
    float aspect = res.x / res.y;
    float d = clamp(dawn, 0.0, 1.0);

    // Sky gradient: three bands that warm as dawn arrives.
    float3 nightTop = float3(0.031, 0.043, 0.118);
    float3 nightMid = float3(0.055, 0.078, 0.188);
    float3 nightLow = float3(0.169, 0.141, 0.322);
    float3 dawnTop  = float3(0.157, 0.169, 0.408);
    float3 dawnMid  = float3(0.588, 0.396, 0.596);
    float3 dawnLow  = float3(1.000, 0.690, 0.455);
    float3 top = mix(nightTop, dawnTop, d);
    float3 mid = mix(nightMid, dawnMid, d * d);
    float3 low = mix(nightLow, dawnLow, d);
    float y = uv.y / horizon;
    float3 col = y < 0.55 ? mix(top, mid, smoothstep(0.0, 0.55, y)) : mix(mid, low, smoothstep(0.55, 1.0, y));

    // Slow drifting haze.
    float n = noise(float2(uv.x * 3.0 + time * 0.012, uv.y * 7.0));
    col += (n - 0.5) * 0.035 * (0.4 + d);

    // Stars: fade out with dawn and towards the horizon.
    float2 g = float2(uv.x * aspect, uv.y) * 90.0;
    float2 cell = floor(g);
    float r = hash(cell);
    if (r > 0.965) {
        float2 c = cell + float2(hash(cell + 7.1), hash(cell + 3.3));
        float dist = length(g - c);
        float tw = 0.55 + 0.45 * sin(time * (1.2 + r * 3.0) + r * 40.0);
        float s = smoothstep(0.16, 0.0, dist) * tw * (1.0 - smoothstep(0.1, 0.75, d)) * (1.0 - smoothstep(0.35, 0.95, y));
        col += float3(s) * float3(1.0, 0.96, 0.9);
    }

    // The sun: rises from below the horizon as dawn grows.
    float sunY = horizon + 0.12 - d * 0.30;
    float2 sp = float2(sunX * aspect, sunY);
    float2 p = float2(uv.x * aspect, uv.y);
    float sd = length(p - sp);
    float3 sunCol = float3(1.0, 0.82, 0.42);
    float glow = exp(-sd * 3.2) * (0.25 + 0.9 * d);
    float disk = smoothstep(0.085, 0.078, sd);
    col += sunCol * glow * 0.85;
    col = mix(col, float3(1.0, 0.93, 0.74), disk * smoothstep(-0.02, 0.02, horizon - uv.y));

    // Land: two soft ridges below the horizon line.
    float ridge1 = horizon + 0.018 * sin(uv.x * 6.0 + 1.3) + 0.012 * sin(uv.x * 15.0);
    float ridge2 = horizon + 0.045 + 0.02 * sin(uv.x * 4.0 + 4.0) + 0.008 * sin(uv.x * 21.0 + 2.0);
    float3 land1 = mix(float3(0.067, 0.067, 0.157), float3(0.333, 0.200, 0.290), d);
    float3 land2 = mix(float3(0.035, 0.039, 0.098), float3(0.180, 0.110, 0.180), d);
    col = mix(col, land1, smoothstep(ridge1, ridge1 + 0.003, uv.y));
    col = mix(col, land2, smoothstep(ridge2, ridge2 + 0.003, uv.y));

    // Film grain keeps the gradients from banding.
    col += (hash(fc + time) - 0.5) * 0.02;
    return half4(half3(col), 1.0);
}
"""

/** How far into sunrise the real sky is at this local time (0 at night, 1 by morning). */
fun dawnForTime(t: LocalTime = LocalTime.now()): Float {
    val h = t.hour + t.minute / 60f
    return when {
        h < 4.5f -> 0.05f
        h < 7.5f -> 0.05f + (h - 4.5f) / 3f * 0.95f
        h < 17.5f -> 1f
        h < 20f -> 1f - (h - 17.5f) / 2.5f * 0.95f
        else -> 0.05f
    }
}

@Composable
fun SkyBackground(
    dawn: Float,
    modifier: Modifier = Modifier,
    sunX: Float = 0.68f,
    horizon: Float = 0.86f,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.fillMaxSize()) {
        if (Build.VERSION.SDK_INT >= 33) ShaderSky(dawn, sunX, horizon) else GradientSky(dawn, horizon)
        content()
    }
}

@RequiresApi(33)
@Composable
private fun ShaderSky(dawn: Float, sunX: Float, horizon: Float) {
    val shader = remember { RuntimeShader(SKY_AGSL) }
    val brush = remember { ShaderBrush(shader) }
    var time by remember { mutableFloatStateOf(0f) }
    val reduceMotion = rememberReduceMotion()
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            // ~30 fps is plenty for twinkling stars and keeps the battery happy.
            withFrameNanos { now -> time = (now - start) / 1e9f }
            kotlinx.coroutines.delay(33)
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        shader.setFloatUniform("res", size.width, size.height)
        shader.setFloatUniform("time", time)
        shader.setFloatUniform("dawn", dawn)
        shader.setFloatUniform("sunX", sunX)
        shader.setFloatUniform("horizon", horizon)
        drawRect(brush)
    }
}

@Composable
private fun GradientSky(dawn: Float, horizon: Float) {
    val top = lerp(Rise.NightDeep, Color(0xFF282B68), dawn)
    val mid = lerp(Rise.Night, Color(0xFF966598), dawn * dawn)
    val low = lerp(Rise.Dusk, Rise.Apricot, dawn)
    val stars = remember { List(70) { Triple(Math.random().toFloat(), Math.random().toFloat() * 0.7f, Math.random().toFloat()) } }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(0f to top, 0.5f to mid, horizon to low, 1f to low))
        val alpha = (1f - dawn * 1.4f).coerceIn(0f, 1f)
        stars.forEach { (x, y, s) ->
            drawCircle(Color.White.copy(alpha = alpha * (0.4f + s * 0.6f)), radius = 1.2f + s * 1.6f, center = Offset(x * size.width, y * size.height))
        }
        val sunY = (horizon + 0.12f - dawn * 0.3f) * size.height
        drawCircle(
            Brush.radialGradient(listOf(Rise.Sun.copy(alpha = 0.55f * dawn + 0.1f), Color.Transparent), center = Offset(size.width * 0.68f, sunY), radius = size.width * 0.6f),
            radius = size.width * 0.6f, center = Offset(size.width * 0.68f, sunY),
        )
    }
}

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Point on a circle, used by the sun dial. */
fun polar(cx: Float, cy: Float, r: Float, angle: Float) = Offset(cx + r * cos(angle), cy + r * sin(angle))
const val TAU = (2 * PI).toFloat()
