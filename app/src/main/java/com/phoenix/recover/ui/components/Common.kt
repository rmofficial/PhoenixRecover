package com.phoenix.recover.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.composed
import android.util.LruCache
import com.phoenix.recover.engine.MediaKind
import com.phoenix.recover.engine.RecoveredFile
import com.phoenix.recover.ui.Screen
import com.phoenix.recover.ui.theme.BgDeep
import com.phoenix.recover.ui.theme.LocalAccent
import com.phoenix.recover.ui.theme.TextPrimary
import com.phoenix.recover.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.random.Random

fun Modifier.pressClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "press",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

@Composable
fun PhoenixBackground(content: @Composable () -> Unit) {
    val accent = LocalAccent.current
    val stars = remember {
        val rnd = Random(11)
        List(54) {
            Triple(rnd.nextFloat(), rnd.nextFloat(), 0.35f + rnd.nextFloat() * 0.9f)
        }
    }
    val backdrop = remember(accent) {
        Modifier.drawBehind {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(accent.a.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(size.width * 0.08f, size.height * 0.02f),
                    radius = size.width * 0.95f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(accent.b.copy(alpha = 0.15f), Color.Transparent),
                    center = Offset(size.width * 0.98f, size.height * 0.34f),
                    radius = size.width * 0.85f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(accent.c.copy(alpha = 0.08f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 1.02f),
                    radius = size.width * 1.0f,
                ),
            )
            stars.forEach { (x, y, a) ->
                drawCircle(
                    color = Color.White.copy(alpha = 0.10f * a),
                    radius = 0.7.dp.toPx() * a,
                    center = Offset(size.width * x, size.height * y),
                )
            }
        }
    }
    Box(Modifier.fillMaxSize().background(BgDeep).then(backdrop)) {
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 22.dp,
    padding: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    contentPadding: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(corner)
    val base = if (onClick != null) modifier.pressClickable(onClick = onClick) else modifier
    Box(
        base
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.070f), Color.White.copy(alpha = 0.024f)),
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        accent.a.copy(alpha = 0.38f),
                        Color.White.copy(alpha = 0.06f),
                        accent.b.copy(alpha = 0.26f),
                    ),
                ),
                shape,
            ),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(accent.a.copy(alpha = 0.10f), Color.Transparent),
                            center = Offset(size.width * 0.15f, 0f),
                            radius = size.width * 0.8f,
                        ),
                    )
                },
        )
        Column(
            if (contentPadding) Modifier.padding(padding) else Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
fun GradientText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    colors: List<Color>? = null,
) {
    val accent = LocalAccent.current
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(
            brush = Brush.linearGradient(colors ?: listOf(accent.a, accent.b, accent.c)),
        ),
    )
}

@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(17.dp)
    val brush = if (enabled) {
        Brush.horizontalGradient(listOf(accent.a, accent.b, accent.c))
    } else {
        Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.06f)))
    }
    Box(
        modifier
            .height(54.dp)
            .then(if (enabled && !loading) Modifier.pressClickable(onClick = onClick) else Modifier)
            .clip(shape)
            .background(brush),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            leading?.invoke()
            Text(
                text = if (loading) "Working…" else text,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = if (enabled) Color(0xFF04121A) else TextSecondary,
            )
        }
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(17.dp)
    Box(
        modifier
            .height(52.dp)
            .then(if (enabled) Modifier.pressClickable(onClick = onClick) else Modifier)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
            color = if (enabled) accent.a else TextSecondary,
        )
    }
}

@Composable
fun PillChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    count: Int? = null,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(50)
    val bg = if (selected) {
        Brush.horizontalGradient(listOf(accent.a.copy(alpha = 0.22f), accent.b.copy(alpha = 0.20f)))
    } else {
        Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.035f)))
    }
    val textColor by animateColorAsState(if (selected) accent.a else TextSecondary, label = "chip")
    Row(
        modifier
            .height(34.dp)
            .pressClickable(onClick = onClick)
            .clip(shape)
            .background(bg)
            .border(
                1.dp,
                if (selected) accent.a.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.075f),
                shape,
            )
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 0.5.sp,
            ),
            color = textColor,
        )
        if (count != null && count > 0) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = if (selected) accent.a.copy(alpha = 0.9f) else TextSecondary.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp, fontSize = 10.sp),
            color = TextSecondary.copy(alpha = 0.85f),
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun Metric(value: String, label: String, modifier: Modifier = Modifier, accentColor: Color? = null) {
    val accent = LocalAccent.current
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
            color = accentColor ?: TextPrimary,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.1.sp),
            color = TextSecondary,
            maxLines = 1,
        )
    }
}

@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(40.dp)
                    .pressClickable(onClick = onBack)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 20.sp),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp),
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

private data class NavItem(val screen: Screen, val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem(Screen.HOME, "Home", Icons.Filled.Home),
    NavItem(Screen.RESULTS, "Results", Icons.Filled.List),
    NavItem(Screen.VAULT, "Vault", Icons.Filled.CheckCircle),
    NavItem(Screen.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun BottomNav(current: Screen, onSelect: (Screen) -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .height(66.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xF20A0F1A))
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.10f), accent.a.copy(alpha = 0.18f), Color.White.copy(alpha = 0.06f)),
                ),
                RoundedCornerShape(24.dp),
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navItems.forEach { item ->
            val selected = current == item.screen
            val tint by animateColorAsState(
                if (selected) accent.a else TextSecondary.copy(alpha = 0.8f),
                label = "navTint",
            )
            val bgAlpha by animateFloatAsState(if (selected) 1f else 0f, label = "navBg")
            Column(
                Modifier
                    .weight(1f)
                    .pressClickable { onSelect(item.screen) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 40.dp, height = 28.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    accent.a.copy(alpha = 0.20f * bgAlpha),
                                    accent.b.copy(alpha = 0.16f * bgAlpha),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.4.sp),
                    color = tint,
                )
            }
        }
    }
}

@Composable
fun PhoenixMark(size: Dp = 38.dp) {
    val accent = LocalAccent.current
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val center = Offset(this.size.width / 2, this.size.height / 2)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(accent.a.copy(alpha = 0.28f), Color.Transparent),
                center = center,
                radius = s * 0.6f,
            ),
            radius = s * 0.6f,
            center = center,
        )
        drawArc(
            brush = Brush.sweepGradient(listOf(accent.a, accent.b, accent.c, accent.a)),
            startAngle = 118f,
            sweepAngle = 384f,
            useCenter = false,
            topLeft = Offset(s * 0.18f, s * 0.18f),
            size = Size(s * 0.64f, s * 0.64f),
            style = Stroke(width = s * 0.09f, cap = StrokeCap.Round),
        )
        val path = Path().apply {
            moveTo(s * 0.50f, s * 0.10f)
            lineTo(s * 0.72f, s * 0.26f)
            lineTo(s * 0.50f, s * 0.40f)
            close()
        }
        drawPath(path, accent.b)
        drawCircle(accent.c, radius = s * 0.075f, center = center)
        drawCircle(Color.White, radius = s * 0.032f, center = center)
    }
}

@Composable
fun KindGlyph(
    kind: MediaKind,
    modifier: Modifier = Modifier,
    tint: Color = LocalAccent.current.a,
    strokeWidth: Dp = 1.7.dp,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = min(w, h)
        val sw = strokeWidth.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val left = (w - s) / 2
        val top = (h - s) / 2
        when (kind) {
            MediaKind.IMAGE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(left + s * 0.10f, top + s * 0.14f),
                    size = Size(s * 0.80f, s * 0.72f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.12f),
                    style = stroke,
                )
                drawCircle(tint, radius = s * 0.075f, center = Offset(left + s * 0.66f, top + s * 0.34f))
                val p = Path().apply {
                    moveTo(left + s * 0.14f, top + s * 0.80f)
                    lineTo(left + s * 0.42f, top + s * 0.50f)
                    lineTo(left + s * 0.60f, top + s * 0.68f)
                    lineTo(left + s * 0.72f, top + s * 0.56f)
                    lineTo(left + s * 0.88f, top + s * 0.78f)
                }
                drawPath(p, tint, style = stroke)
            }
            MediaKind.VIDEO -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(left + s * 0.10f, top + s * 0.18f),
                    size = Size(s * 0.80f, s * 0.64f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.14f),
                    style = stroke,
                )
                val p = Path().apply {
                    moveTo(left + s * 0.43f, top + s * 0.35f)
                    lineTo(left + s * 0.67f, top + s * 0.50f)
                    lineTo(left + s * 0.43f, top + s * 0.65f)
                    close()
                }
                drawPath(p, tint)
            }
            MediaKind.AUDIO -> {
                drawCircle(tint, radius = s * 0.10f, center = Offset(left + s * 0.36f, top + s * 0.70f), style = stroke)
                drawCircle(tint, radius = s * 0.07f, center = Offset(left + s * 0.70f, top + s * 0.74f), style = stroke)
                drawLine(tint, Offset(left + s * 0.46f, top + s * 0.70f), Offset(left + s * 0.46f, top + s * 0.28f), sw, StrokeCap.Round)
                drawLine(tint, Offset(left + s * 0.77f, top + s * 0.74f), Offset(left + s * 0.77f, top + s * 0.34f), sw, StrokeCap.Round)
                drawLine(tint, Offset(left + s * 0.46f, top + s * 0.28f), Offset(left + s * 0.77f, top + s * 0.34f), sw, StrokeCap.Round)
            }
            MediaKind.DOCUMENT -> {
                val p = Path().apply {
                    moveTo(left + s * 0.24f, top + s * 0.12f)
                    lineTo(left + s * 0.60f, top + s * 0.12f)
                    lineTo(left + s * 0.78f, top + s * 0.30f)
                    lineTo(left + s * 0.78f, top + s * 0.88f)
                    lineTo(left + s * 0.24f, top + s * 0.88f)
                    close()
                }
                drawPath(p, tint, style = stroke)
                drawLine(tint, Offset(left + s * 0.60f, top + s * 0.12f), Offset(left + s * 0.60f, top + s * 0.30f), sw)
                drawLine(tint, Offset(left + s * 0.60f, top + s * 0.30f), Offset(left + s * 0.78f, top + s * 0.30f), sw)
                drawLine(tint, Offset(left + s * 0.35f, top + s * 0.50f), Offset(left + s * 0.67f, top + s * 0.50f), sw, StrokeCap.Round)
                drawLine(tint, Offset(left + s * 0.35f, top + s * 0.63f), Offset(left + s * 0.67f, top + s * 0.63f), sw, StrokeCap.Round)
                drawLine(tint, Offset(left + s * 0.35f, top + s * 0.76f), Offset(left + s * 0.55f, top + s * 0.76f), sw, StrokeCap.Round)
            }
            MediaKind.ARCHIVE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(left + s * 0.14f, top + s * 0.30f),
                    size = Size(s * 0.72f, s * 0.54f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.10f),
                    style = stroke,
                )
                drawLine(tint, Offset(left + s * 0.14f, top + s * 0.44f), Offset(left + s * 0.86f, top + s * 0.44f), sw)
                drawLine(tint, Offset(left + s * 0.46f, top + s * 0.30f), Offset(left + s * 0.54f, top + s * 0.30f), sw * 1.6f, StrokeCap.Round)
                drawLine(tint, Offset(left + s * 0.46f, top + s * 0.38f), Offset(left + s * 0.54f, top + s * 0.38f), sw, StrokeCap.Round)
            }
            MediaKind.OTHER -> {
                drawCircle(tint, radius = s * 0.30f, center = Offset(w / 2, h / 2), style = stroke)
                drawCircle(tint, radius = s * 0.06f, center = Offset(w / 2, h / 2))
            }
        }
    }
}

@Composable
fun KindBadge(kind: MediaKind, size: Dp = 46.dp, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val tint = when (kind) {
        MediaKind.IMAGE -> accent.a
        MediaKind.VIDEO -> accent.b
        MediaKind.AUDIO -> accent.c
        MediaKind.DOCUMENT -> Color(0xFF7DD3FC)
        MediaKind.ARCHIVE -> Color(0xFFFBBF24)
        MediaKind.OTHER -> accent.a
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(
                Brush.linearGradient(
                    listOf(tint.copy(alpha = 0.22f), tint.copy(alpha = 0.07f)),
                ),
            )
            .border(1.dp, tint.copy(alpha = 0.30f), RoundedCornerShape(size / 3.2f)),
        contentAlignment = Alignment.Center,
    ) {
        KindGlyph(kind, Modifier.size(size * 0.55f), tint = tint, strokeWidth = 1.5.dp)
    }
}

@Composable
fun ConfidenceRing(value: Int, size: Dp = 38.dp, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val animated by animateFloatAsState(value.coerceIn(0, 100) / 100f, tween(700), label = "conf")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 3.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.07f), style = Stroke(sw), radius = this.size.minDimension / 2 - sw)
            drawArc(
                brush = Brush.sweepGradient(listOf(accent.a, accent.b, accent.c, accent.a)),
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = Offset(sw, sw),
                size = Size(this.size.width - sw * 2, this.size.height - sw * 2),
                style = Stroke(sw, cap = StrokeCap.Round),
            )
        }
        Text(
            "$value",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
            color = TextPrimary,
        )
    }
}

@Composable
fun FileThumb(file: RecoveredFile, size: Dp = 58.dp, modifier: Modifier = Modifier) {
    val path = file.scratchPath ?: file.sourcePath
    val maxPx = (size.value * 2.4f).toInt().coerceAtLeast(128)
    val fromFile = rememberFileBitmap(path, file.kind == MediaKind.VIDEO, maxPx)
    val fromUri = rememberUriBitmap(if (path == null) file.contentUri else null, maxPx)
    val bitmap = fromFile ?: fromUri
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.4f))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(size / 3.4f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(size / 3.4f)),
            )
        } else {
            KindBadge(file.kind, size * 0.78f)
        }
    }
}

private val thumbCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

private val thumbLoads = kotlinx.coroutines.sync.Semaphore(2)

private suspend fun <T> loadThumbnail(block: () -> T): T = withContext(Dispatchers.IO) {
    thumbLoads.withPermit {
        val previous = runCatching {
            android.os.Process.getThreadPriority(android.os.Process.myTid())
        }.getOrDefault(android.os.Process.THREAD_PRIORITY_DEFAULT)
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        }
        try {
            block()
        } finally {
            runCatching { android.os.Process.setThreadPriority(previous) }
        }
    }
}

private fun thumbKey(path: String, video: Boolean, maxPx: Int): String {
    val modified = runCatching { java.io.File(path).lastModified() }.getOrDefault(0L)
    return "$path|$video|$maxPx|$modified"
}

@Composable
fun rememberFileBitmap(path: String?, video: Boolean, maxPx: Int): ImageBitmap? {
    val key = path?.let { thumbKey(it, video, maxPx) }
    val cached = key?.let { thumbCache.get(it) }
    val state = produceState<ImageBitmap?>(initialValue = cached, key1 = key) {
        if (value != null || path == null) return@produceState
        val loaded = loadThumbnail {
            runCatching { loadBitmap(path, video, maxPx) }.getOrNull()
        }
        if (loaded != null && key != null) thumbCache.put(key, loaded)
        value = loaded
    }
    return state.value
}

@Composable
fun rememberUriBitmap(uriString: String?, maxPx: Int): ImageBitmap? {
    val context = LocalContext.current
    val key = uriString?.let { "uri:$it|$maxPx" }
    val cached = key?.let { thumbCache.get(it) }
    val state = produceState<ImageBitmap?>(initialValue = cached, key1 = key) {
        if (value != null || uriString == null) return@produceState
        val loaded = loadThumbnail {
            runCatching { loadUriBitmap(context, uriString, maxPx) }.getOrNull()
        }
        if (loaded != null && key != null) thumbCache.put(key, loaded)
        value = loaded
    }
    return state.value
}

private fun loadUriBitmap(context: android.content.Context, uriString: String, maxPx: Int): ImageBitmap? {
    val uri = android.net.Uri.parse(uriString)
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, opts)
    } ?: return null
    return bmp.asImageBitmap()
}

private fun loadBitmap(path: String, video: Boolean, maxPx: Int): ImageBitmap? {
    if (video) {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val frame = retriever.getFrameAtTime(0) ?: return null
            val scale = maxPx.toFloat() / maxOf(frame.width, frame.height).coerceAtLeast(1)
            val bmp = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                frame
            }
            bmp.asImageBitmap()
        } finally {
            runCatching { retriever.release() }
        }
    }
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = thumbConfig(path)
    }
    val bmp = android.graphics.BitmapFactory.decodeFile(path, opts) ?: return null
    return bmp.asImageBitmap()
}

private fun thumbConfig(path: String): android.graphics.Bitmap.Config {
    val ext = path.substringAfterLast('.', "").lowercase()
    return if (ext == "png" || ext == "gif" || ext == "webp") {
        android.graphics.Bitmap.Config.ARGB_8888
    } else {
        android.graphics.Bitmap.Config.RGB_565
    }
}

@Composable
fun SelectDot(selected: Boolean, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val accent = LocalAccent.current
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (selected) {
                    Brush.linearGradient(listOf(accent.a, accent.b))
                } else {
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.05f)))
                },
            )
            .border(
                1.dp,
                if (selected) Color.Transparent else Color.White.copy(alpha = 0.20f),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF04121A), modifier = Modifier.size(size * 0.6f))
        }
    }
}

@Composable
fun PulseDot(color: Color = LocalAccent.current.a, size: Dp = 6.dp, animated: Boolean = true) {
    val alpha = if (animated) {
        val infinite = rememberInfiniteTransition(label = "pulse")
        infinite.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        ).value
    } else {
        0.85f
    }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

@Composable
fun AccessPill(granted: Boolean) {
    val accent = LocalAccent.current
    val color = if (granted) Color(0xFF34D399) else Color(0xFFFBBF24)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        Text(
            if (granted) "FULL ACCESS" else "LIMITED",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, letterSpacing = 0.8.sp),
            color = color,
        )
    }
}

@Composable
fun ProChip() {
    val accent = LocalAccent.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(accent.a.copy(alpha = 0.18f), accent.b.copy(alpha = 0.20f))))
            .border(1.dp, accent.a.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("PRO", style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.9.sp), color = accent.a)
    }
}

@Composable
fun EmptyOrb(size: Dp = 130.dp) {
    val accent = LocalAccent.current
    val infinite = rememberInfiniteTransition(label = "empty")
    val rotation = infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
        ),
        label = "emptyRot",
    ).value
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val center = Offset(this.size.width / 2, this.size.height / 2)
        drawCircle(
            Brush.radialGradient(listOf(accent.a.copy(alpha = 0.18f), Color.Transparent), center = center, radius = s * 0.5f),
            radius = s * 0.5f,
            center = center,
        )
        rotate(rotation, center) {
            drawArc(
                brush = Brush.sweepGradient(listOf(accent.a, accent.b, accent.c, Color.Transparent, accent.a)),
                startAngle = 0f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = Offset(s * 0.14f, s * 0.14f),
                size = Size(s * 0.72f, s * 0.72f),
                style = Stroke(s * 0.02f, cap = StrokeCap.Round),
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(accent.b, Color.Transparent, accent.b)),
                startAngle = 140f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.25f, s * 0.25f),
                size = Size(s * 0.5f, s * 0.5f),
                style = Stroke(s * 0.012f, cap = StrokeCap.Round),
            )
        }
        drawCircle(Color.White.copy(alpha = 0.08f), radius = s * 0.10f, center = center, style = Stroke(s * 0.01f))
        drawCircle(accent.c.copy(alpha = 0.8f), radius = s * 0.022f, center = center)
    }
}


