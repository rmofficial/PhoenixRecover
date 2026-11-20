package com.phoenix.recover.ui.screens

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoenix.recover.RecoverViewModel
import com.phoenix.recover.ui.Screen
import com.phoenix.recover.ui.UiState
import com.phoenix.recover.ui.components.FileThumb
import com.phoenix.recover.ui.components.GhostButton
import com.phoenix.recover.ui.components.GlassCard
import com.phoenix.recover.ui.components.GradientButton
import com.phoenix.recover.ui.components.Metric
import com.phoenix.recover.ui.components.PulseDot
import com.phoenix.recover.ui.components.RecoveryOrb
import com.phoenix.recover.ui.components.SectionLabel
import com.phoenix.recover.ui.components.TopBar
import com.phoenix.recover.ui.theme.Danger
import com.phoenix.recover.ui.theme.LocalAccent
import com.phoenix.recover.ui.theme.TextPrimary
import com.phoenix.recover.ui.theme.TextSecondary
import com.phoenix.recover.ui.theme.Warn
import com.phoenix.recover.util.Fmt
import kotlinx.coroutines.delay

@Composable
fun ScanScreen(state: UiState, vm: RecoverViewModel) {
    val accent = LocalAccent.current
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.scanning) {
        while (state.scanning) {
            now = SystemClock.elapsedRealtime()
            delay(900)
        }
    }
    val elapsed = if (state.scanning) (now - state.scanStartedAt).coerceAtLeast(0) else (state.lastScan?.elapsedMs ?: 0)
    val recent = remember(state.results) { state.results.takeLast(8).reversed() }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "Deep scan",
            subtitle = if (state.scanning) "Scanning trash, hidden folders and media index" else state.scanStatus,
            onBack = { vm.back() },
        )
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RecoveryOrb(
                        active = state.scanning,
                        title = if (state.scanning) "LIVE SCAN" else "SCAN COMPLETE",
                        value = Fmt.count(state.foundCount),
                        caption = "FILES FOUND",
                        size = 258.dp,
                        particles = 18,
                    )
                }
            }

            item {
                GlassCard(padding = 15.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.scanning) {
                            PulseDot(accent.a, 8.dp)
                        } else {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(accent.a.copy(alpha = 0.4f)))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (state.scanning) "Sweeping storage…" else state.scanStatus.ifBlank { "Ready" },
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.sp),
                                color = TextPrimary,
                            )
                            if (state.scanning && state.scanPath.isNotEmpty()) {
                                Text(
                                    Fmt.shortPath(state.scanPath, 54),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp),
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else if (!state.scanning && state.lastScan != null) {
                                Text(
                                    "${Fmt.count(state.lastScan.filesInspected)} files inspected in ${Fmt.count(state.lastScan.dirsVisited)} folders",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.5.sp),
                                    color = TextSecondary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(13.dp))
                    ScanBar(active = state.scanning)
                }
            }

            item {
                GlassCard(padding = 16.dp) {
                    Row {
                        Metric(Fmt.count(state.scanDirs), "folders", Modifier.weight(1f), accent.a)
                        Metric(Fmt.count(state.scanFiles), "files", Modifier.weight(1f), TextPrimary)
                        Metric(Fmt.count(state.foundCount), "found", Modifier.weight(1f), accent.b)
                        Metric(Fmt.duration(elapsed), "elapsed", Modifier.weight(1f), TextPrimary)
                    }
                }
            }

            if (!state.hasAccess) {
                item {
                    GlassCard(padding = 15.dp) {
                        Text(
                            "Limited access",
                            style = MaterialTheme.typography.titleMedium,
                            color = Warn,
                        )
                        Text(
                            "Enable All files access for complete trash, hidden-folder and orphan detection.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp),
                            color = TextSecondary,
                        )
                    }
                }
            }

            if (recent.isNotEmpty()) {
                item {
                    SectionLabel("Found so far") {
                        Text(
                            "${Fmt.count(state.foundCount)} items",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent.a,
                        )
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recent, key = { it.id }) { file ->
                            GlassCard(
                                modifier = Modifier.width(98.dp),
                                padding = 8.dp,
                                corner = 16.dp,
                                onClick = { vm.showPreview(file) },
                            ) {
                                FileThumb(file, 82.dp, Modifier.fillMaxWidth())
                                Spacer(Modifier.height(7.dp))
                                Text(
                                    file.name,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Column {
                    if (state.scanning) {
                        GhostButton("Stop scan", Modifier.fillMaxWidth()) { vm.cancelScan() }
                        Spacer(Modifier.height(7.dp))
                        Text(
                            "You can stop at any time. Everything already found is kept.",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                            color = TextSecondary.copy(alpha = 0.75f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        GradientButton(
                            text = if (state.foundCount > 0) "View ${Fmt.count(state.foundCount)} recoveries" else "Run another scan",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (state.foundCount > 0) vm.goTab(Screen.RESULTS) else vm.startDeepScan(false)
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GhostButton("Deep scan again", Modifier.weight(1f)) { vm.startDeepScan(false) }
                            GhostButton("Trash bins", Modifier.weight(1f)) { vm.startDeepScan(true) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun ScanBar(active: Boolean) {
    val accent = LocalAccent.current
    val phaseState = if (active) {
        val infinite = rememberInfiniteTransition(label = "scanbar")
        infinite.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(1500, easing = LinearEasing)),
            label = "phase",
        )
    } else {
        null
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(6.dp),
    ) {
        val phase = phaseState?.value ?: 0f
        val h = size.height
        drawRoundRect(
            color = Color.White.copy(alpha = 0.06f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
            size = Size(size.width, h),
        )
        if (active) {
            val segment = size.width * 0.32f
            val x = (size.width + segment) * phase - segment
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, accent.a, accent.b, Color.Transparent),
                    startX = x,
                    endX = x + segment,
                ),
                topLeft = Offset(x, 0f),
                size = Size(segment, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
            )
        } else {
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(accent.a, accent.b, accent.c)),
                size = Size(size.width, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
            )
        }
    }
}
