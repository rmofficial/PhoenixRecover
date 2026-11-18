package com.phoenix.recover.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoenix.recover.ui.theme.LocalAccent
import com.phoenix.recover.ui.theme.TextPrimary
import com.phoenix.recover.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun RecoveryOrb(
    modifier: Modifier = Modifier,
    active: Boolean,
    progress: Float? = null,
    title: String = "",
    value: String = "",
    caption: String = "",
    size: Dp = 220.dp,
    particles: Int = 20,
) {
    val accent = LocalAccent.current
    val animated = active || progress != null
    val infinite = rememberInfiniteTransition(label = "orb")
    val spin = infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (active) 7000 else 24000, easing = LinearEasing)),
        label = "spin",
    ).value
    val counterRaw = infinite.animateFloat(
        360f, 0f,
        infiniteRepeatable(tween(11000, easing = LinearEasing)),
        label = "counter",
    ).value
    val pulseRaw = infinite.animateFloat(
        0.94f, 1.04f,
        infiniteRepeatable(tween(1900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    ).value
    val shimmerRaw = infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(3600, easing = LinearEasing)),
        label = "shimmer",
    ).value

    // Idle orbs keep the slow spin; fast sweeps only run while working.
    val counterSpin = if (animated) counterRaw else 0f
    val pulse = if (animated) pulseRaw else 0.97f
    val shimmer = if (animated) shimmerRaw else 0.35f

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = this.size.minDimension
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val glow = s * 0.62f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.a.copy(alpha = 0.22f), accent.b.copy(alpha = 0.10f), Color.Transparent),
                    center = c,
                    radius = glow,
                ),
                radius = glow,
                center = c,
            )

            repeat(particles) { i ->
                val seed = i * 2.399963f
                val speed = 0.55f + (i % 5) * 0.11f
                val angle = seed + (spin * speed) * (PI / 180.0).toFloat()
                val orbit = s * (0.34f + (i % 4) * 0.035f)
                val x = c.x + cos(angle) * orbit
                val y = c.y + sin(angle) * orbit
                val r = (0.8f + (i % 3) * 0.5f).dp.toPx()
                val alpha = 0.18f + (i % 5) * 0.07f
                drawCircle(
                    color = if (i % 3 == 0) accent.c.copy(alpha = alpha) else accent.a.copy(alpha = alpha),
                    radius = r,
                    center = Offset(x, y),
                )
            }

            drawCircle(Color.White.copy(alpha = 0.06f), radius = s * 0.44f, center = c, style = Stroke(1.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.035f), radius = s * 0.38f, center = c, style = Stroke(1.dp.toPx()))

            rotate(spin, c) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(accent.a.copy(alpha = 0.0f), accent.a, accent.b, accent.c, Color.Transparent),
                        center = c,
                    ),
                    startAngle = 20f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = Offset(c.x - s * 0.44f, c.y - s * 0.44f),
                    size = Size(s * 0.88f, s * 0.88f),
                    style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, accent.c, Color.Transparent),
                        center = c,
                    ),
                    startAngle = 210f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(c.x - s * 0.44f, c.y - s * 0.44f),
                    size = Size(s * 0.88f, s * 0.88f),
                    style = Stroke(1.1.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            if (animated) {
                rotate(counterSpin, c) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(Color.Transparent, accent.b.copy(alpha = 0.9f), accent.a, Color.Transparent),
                            center = c,
                        ),
                        startAngle = 90f,
                        sweepAngle = 70f,
                        useCenter = false,
                        topLeft = Offset(c.x - s * 0.35f, c.y - s * 0.35f),
                        size = Size(s * 0.70f, s * 0.70f),
                        style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }

            if (progress != null) {
                val rectTop = c.y - s * 0.49f
                drawCircle(
                    color = Color.White.copy(alpha = 0.06f),
                    radius = s * 0.49f,
                    center = c,
                    style = Stroke(3.dp.toPx()),
                )
                drawArc(
                    brush = Brush.sweepGradient(listOf(accent.a, accent.b, accent.c, accent.a), center = c),
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(c.x - s * 0.49f, rectTop),
                    size = Size(s * 0.98f, s * 0.98f),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.16f),
                        accent.a.copy(alpha = 0.22f),
                        accent.b.copy(alpha = 0.10f),
                        Color.Transparent,
                    ),
                    center = Offset(c.x - s * 0.08f, c.y - s * 0.10f),
                    radius = s * 0.30f * pulse,
                ),
                radius = s * 0.30f,
                center = c,
            )
            drawCircle(
                brush = Brush.sweepGradient(listOf(accent.a, accent.b, accent.c, accent.a), center = c),
                radius = s * 0.305f,
                center = c,
                style = Stroke(1.3.dp.toPx()),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.045f),
                radius = s * 0.235f,
                center = c,
                style = Stroke(1.dp.toPx()),
            )

            if (animated) {
                val shimmerAngle = shimmer * 360f
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                        center = c,
                    ),
                    startAngle = shimmerAngle,
                    sweepAngle = 26f,
                    useCenter = false,
                    topLeft = Offset(c.x - s * 0.22f, c.y - s * 0.22f),
                    size = Size(s * 0.44f, s * 0.44f),
                    style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            drawCircle(accent.c.copy(alpha = 0.9f), radius = s * 0.016f, center = c)
        }

        if (value.isNotEmpty() || title.isNotEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (title.isNotEmpty()) {
                    Text(
                        title.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, letterSpacing = 1.8.sp),
                        color = TextSecondary,
                    )
                }
                if (value.isNotEmpty()) {
                    Text(
                        value,
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontSize = if (value.length > 6) 24.sp else 30.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = TextPrimary,
                        maxLines = 1,
                    )
                }
                if (caption.isNotEmpty()) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.4.sp),
                        color = TextSecondary.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

@Composable
fun WaveStrip(
    values: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 46.dp,
) {
    val accent = LocalAccent.current
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val step = w / (values.size - 1)
        val path = Path()
        val fill = Path()
        values.forEachIndexed { i, raw ->
            val v = raw.coerceIn(0f, 1f)
            val x = i * step
            val y = h - (v * h * 0.85f) - h * 0.05f
            if (i == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, h)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(w, h)
        fill.close()
        drawPath(
            fill,
            Brush.verticalGradient(listOf(accent.a.copy(alpha = 0.22f), Color.Transparent)),
        )
        drawPath(
            path,
            Brush.horizontalGradient(listOf(accent.a, accent.b, accent.c)),
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
        )
        val last = values.last().coerceIn(0f, 1f)
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White, accent.a), center = Offset(w, h - last * h * 0.85f - h * 0.05f), radius = 8.dp.toPx()),
            radius = 3.dp.toPx(),
            center = Offset(w, h - last * h * 0.85f - h * 0.05f),
        )
    }
}
