package guru.liquid.embysonic.ui.brand

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private val LiquidSurface = Color(0xFF131A2E)
private val PrimaryCyan = Color(0xFF4FC3F7)
private val HighlightCyan = Color(0xFF81D4FA)
private val DeepCyan = Color(0xFF35AEE4)

private data class BarSpec(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val phase: Float,
)

private val Bars = listOf(
    BarSpec(48f, 91f, 9f, 82f, -0.18f),
    BarSpec(62f, 78f, 9f, 108f, -0.06f),
    BarSpec(76f, 96f, 9f, 72f, -0.25f),
    BarSpec(90f, 106f, 9f, 58f, -0.02f),
    BarSpec(104f, 88f, 9f, 94f, -0.33f),
    BarSpec(118f, 98f, 9f, 72f, -0.12f),
    BarSpec(132f, 105f, 9f, 60f, -0.41f),
    BarSpec(146f, 85f, 9f, 100f, -0.17f),
    BarSpec(160f, 96f, 9f, 72f, -0.29f),
    BarSpec(174f, 80f, 9f, 104f, -0.08f),
    BarSpec(188f, 92f, 9f, 82f, -0.36f),
)

private const val LiquidWPath =
    "M42 105 C55 77, 72 70, 84 111 C91 137, 96 161, 107 162 C119 163, 125 127, 134 110 C142 94, 150 94, 157 111 C166 136, 170 158, 181 158 C192 158, 199 116, 215 93 C220 86, 231 93, 226 102 C208 133, 201 187, 178 188 C154 189, 149 150, 140 126 C132 151, 124 189, 106 189 C84 189, 78 151, 70 124 C63 99, 57 99, 51 113 C48 120, 38 113, 42 105 Z"

private const val HighlightPath =
    "M61 101 C70 88, 78 94, 84 119 C91 145, 96 169, 108 171 C123 172, 128 129, 137 112 C143 102, 148 102, 153 114 C163 139, 168 170, 182 168 C194 166, 199 124, 213 103"

@Composable
fun LiquidWaveLogo(
    modifier: Modifier = Modifier,
    animatedBars: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "liquidWave-bars")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1450),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bar-phase",
    )
    val wPath = remember { PathParser().parsePathString(LiquidWPath).toPath() }
    val highlightPath = remember { PathParser().parsePathString(HighlightPath).toPath() }

    Canvas(modifier = modifier) {
        val scaleFactor = size.minDimension / 256f
        val left = (size.width - 256f * scaleFactor) / 2f
        val top = (size.height - 256f * scaleFactor) / 2f

        translate(left = left, top = top) {
            scale(scale = scaleFactor, pivot = Offset.Zero) {
            drawRoundRect(
                color = LiquidSurface,
                size = Size(256f, 256f),
                cornerRadius = CornerRadius(64f, 64f),
            )
            Bars.forEach { bar ->
                val amount = if (animatedBars) {
                    val wave = sin(((phase + bar.phase) * 2f * PI).toFloat())
                    1f + wave * 0.14f
                } else {
                    1f
                }
                val adjustedHeight = bar.height * amount
                val centerY = bar.y + bar.height / 2f
                drawRoundRect(
                    color = PrimaryCyan.copy(alpha = if (animatedBars) 0.34f + (amount - 1f) * 0.7f else 0.34f),
                    topLeft = Offset(bar.x, centerY - adjustedHeight / 2f),
                    size = Size(bar.width, adjustedHeight),
                    cornerRadius = CornerRadius(4.5f, 4.5f),
                )
            }
            drawPath(
                path = wPath,
                brush = Brush.linearGradient(
                    0f to HighlightCyan,
                    0.42f to PrimaryCyan,
                    1f to DeepCyan,
                    start = Offset(42f, 78f),
                    end = Offset(218f, 178f),
                ),
            )
            drawPath(
                path = highlightPath,
                color = HighlightCyan.copy(alpha = 0.78f),
                style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            }
        }
    }
}

@Composable
fun LiquidWaveSplashLogo(
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
) {
    Box(modifier = modifier.size(size)) {
        LiquidWaveLogo(modifier = Modifier.fillMaxSize(), animatedBars = true)
    }
}
