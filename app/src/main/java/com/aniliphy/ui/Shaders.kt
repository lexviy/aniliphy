package com.aniliphy.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

val ShadersSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

enum class ColorVisionMode(val label: String, val uniform: Float) {
    Protanopia("Protanopia", 1f),
    Deuteranopia("Deuteranopia", 2f),
    Tritanopia("Tritanopia", 3f),
}

enum class ShaderKind(val label: String, val declarations: String = "", val body: String = "") {
    Off("Disable"),

    BlueLightFilter(
        "Blue light filter",
        body = "color = mix(color, color * float3(1.0, 0.708757, 0.418232), 0.9);",
    ),

    ColorVision(
        "Color vision",
        declarations = """
            uniform float mode;
            const float3x3 RGB2LMS = float3x3(
                17.8824, 43.5161, 4.11935,
                3.45565, 27.1554, 3.86714,
                0.0299566, 0.184309, 1.46709);
            const float3x3 LMS2RGB = float3x3(
                0.0809444479, -0.130504409, 0.116721066,
                -0.0102485335, 0.0540193266, -0.113614708,
                -0.000365296938, -0.00412161469, 0.693511405);
        """,
        body = """
            float3x3 m;
            if (mode < 1.5) {
                m = float3x3(0.0, 2.02344, -2.52581, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0);
            } else if (mode < 2.5) {
                m = float3x3(1.0, 0.0, 0.0, 0.494207, 0.0, 1.24827, 0.0, 0.0, 1.0);
            } else {
                m = float3x3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, -0.395913, 0.801109, 0.0);
            }
            color = ((color * RGB2LMS) * m) * LMS2RGB;
        """,
    ),

    Custom(
        "Custom",
        body = "color = (color - 0.5) * 1.35 + 0.5;",
    ),

    Grayscale(
        "Grayscale",
        body = "color = float3(dot(color, float3(0.2126, 0.7152, 0.0722)));",
    ),

    InvertColors(
        "Invert colors",
        body = "color = 1.0 - color;",
    ),

    OledSaver(
        "OLED saver",
        declarations = "uniform float swapPhase;",
        body = """
            bool even = mod(floor(p.x) + floor(p.y), 2.0) == 0.0;
            bool keep = swapPhase > 0.5 ? !even : even;
            if (!keep) color = float3(0.0);
        """,
    ),

    Paper(
        "Paper",
        body = """
            float grain = fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453) * 0.12 - 0.06;
            color = clamp(color + grain * 0.7, 0.0, 1.0);
            color = min(color, float3(0.88));
        """,
    ),

    Vibrance(
        "Vibrance",
        body = """
            float top = max(max(color.r, color.g), color.b);
            float bottom = min(min(color.r, color.g), color.b);
            float saturation = top == 0.0 ? 0.0 : (top - bottom) / top;
            float luma = dot(color, float3(0.2126, 0.7152, 0.0722));
            bool skinOrder = color.r > color.g && color.g > color.b;
            bool skinRange = color.r >= 0.4 && color.r <= 0.85
                && color.g >= 0.2 && color.g <= 0.7
                && color.b >= 0.1 && color.b <= 0.5;
            float skin = (skinOrder && skinRange) ? 0.75 : 0.0;
            color = mix(float3(luma), color, 1.0 + (1.0 - saturation) * (1.0 - skin));
        """,
    ),

    Wallbash(
        "Wallbash",
        declarations = "uniform float3 tint;",
        body = """
            float luminance = dot(color, float3(0.2126, 0.7152, 0.0722));
            color = mix(color, mix(tint, float3(luminance), 0.8), 0.2);
        """,
    ),
    ;

    val source: String
        get() = """
            uniform shader tex;
            $declarations
            half4 main(float2 p) {
                half4 pixel = tex.eval(p);
                if (pixel.a <= 0.0) return pixel;
                float3 color = float3(pixel.rgb) / float(pixel.a);
                $body
                color = clamp(color, 0.0, 1.0);
                return half4(half3(color) * pixel.a, pixel.a);
            }
        """.trimIndent()
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun buildEffect(
    kind: ShaderKind,
    tint: Color,
    mode: ColorVisionMode,
    oledSwapped: Boolean,
) = RuntimeShader(kind.source).let { shader ->
    when (kind) {
        ShaderKind.ColorVision -> shader.setFloatUniform("mode", mode.uniform)
        ShaderKind.OledSaver -> shader.setFloatUniform("swapPhase", if (oledSwapped) 1f else 0f)
        ShaderKind.Wallbash -> shader.setFloatUniform("tint", tint.red, tint.green, tint.blue)
        else -> Unit
    }
    RenderEffect.createRuntimeShaderEffect(shader, "tex").asComposeRenderEffect()
}

@Composable
fun Modifier.shaderEffect(kind: ShaderKind, tint: Color, mode: ColorVisionMode): Modifier {
    if (kind == ShaderKind.Off || !ShadersSupported) return this

    val oledSwapped by produceState(false, kind) {
        if (kind != ShaderKind.OledSaver) return@produceState
        while (true) {
            delay(OLED_SWAP_MILLIS)
            value = !value
        }
    }
    val effect = remember(kind, tint, mode, oledSwapped) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            buildEffect(kind, tint, mode, oledSwapped)
        } else {
            null
        }
    }
    return graphicsLayer { renderEffect = effect }
}

private const val OLED_SWAP_MILLIS = 10_000L
