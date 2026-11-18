package com.phoenix.recover.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoenix.recover.RecoverViewModel
import com.phoenix.recover.engine.MediaKind
import com.phoenix.recover.engine.Signatures
import com.phoenix.recover.ui.ActivityEntry
import com.phoenix.recover.ui.ActivityType
import com.phoenix.recover.ui.Screen
import com.phoenix.recover.ui.UiState
import com.phoenix.recover.ui.components.AccessPill
import com.phoenix.recover.ui.components.BottomNav
import com.phoenix.recover.ui.components.EmptyOrb
import com.phoenix.recover.ui.components.FileThumb
import com.phoenix.recover.ui.components.GhostButton
import com.phoenix.recover.ui.components.GlassCard
import com.phoenix.recover.ui.components.GradientButton
import com.phoenix.recover.ui.components.GradientText
import com.phoenix.recover.ui.components.KindBadge
import com.phoenix.recover.ui.components.KindGlyph
import com.phoenix.recover.ui.components.Metric
import com.phoenix.recover.ui.components.PhoenixMark
import com.phoenix.recover.ui.components.ProChip
import com.phoenix.recover.ui.components.PulseDot
import com.phoenix.recover.ui.components.RecoveryOrb
import com.phoenix.recover.ui.components.SectionLabel
import com.phoenix.recover.ui.components.pressClickable
import com.phoenix.recover.ui.theme.Danger
import com.phoenix.recover.ui.theme.LocalAccent
import com.phoenix.recover.ui.theme.TextPrimary
import com.phoenix.recover.ui.theme.TextSecondary
import com.phoenix.recover.ui.theme.Warn
import com.phoenix.recover.util.Fmt
import com.phoenix.recover.util.StorageVolume

@Composable
fun HomeScreen(state: UiState, vm: RecoverViewModel) {
    val accent = LocalAccent.current
    val latest = remember(state.results) {
        state.results.takeLast(8).asReversed()
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhoenixMark(38.dp)
            Spacer(Modifier.width(11.dp))
            Column {
                Text(
                    "PHOENIX",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 3.sp,
                        fontSize = 16.sp,
                    ),
                    color = TextPrimary,
                )
                Text(
                    "RECOVER",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.4.sp, fontSize = 8.sp),
                    color = accent.a,
                )
            }
            Spacer(Modifier.weight(1f))
            AccessPill(state.hasAccess)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(38.dp)
                    .pressClickable { vm.goTab(Screen.SETTINGS) }
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                GlassCard(padding = 18.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RecoveryOrb(
                            active = state.scanning,
                            progress = null,
                            title = if (state.scanning) "SCANNING" else "ENGINE READY",
                            value = if (state.foundCount > 0) Fmt.count(state.foundCount) else "",
                            caption = if (state.foundCount > 0) "FOUND" else "ON-DEVICE",
                            size = 134.dp,
                            particles = 14,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ProChip()
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    "V1.2",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, letterSpacing = 1.sp),
                                    color = TextSecondary,
                                )
                            }
                            Spacer(Modifier.height(9.dp))
                            Text(
                                "Bring deleted\nfiles back.",
                                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 24.sp, lineHeight = 27.sp),
                                color = TextPrimary,
                            )
                            Spacer(Modifier.height(7.dp))
                            Text(
                                "Trash bins, hidden folders, orphaned media and raw signatures.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                                color = TextSecondary,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    GradientButton(
                        text = if (state.scanning) "View live scan" else "Start deep scan",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (state.scanning) vm.push(Screen.SCAN) else vm.startDeepScan(false)
                        },
                    )
                }
            }

            if (!state.hasAccess) {
                item {
                    GlassCard(padding = 15.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Warn.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("!", color = Warn, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Full access is off", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                Text(
                                    "Android is hiding most folders from the scanner.",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                                    color = TextSecondary,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Enable",
                                style = MaterialTheme.typography.labelLarge,
                                color = accent.a,
                                modifier = Modifier.pressClickable { vm.resetPermissionGate() },
                            )
                        }
                    }
                }
            }

            item {
                SectionLabel("Storage") {
                    Text(
                        "${state.volumes.size} volume${if (state.volumes.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                GlassCard(padding = 16.dp) {
                    if (state.volumes.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EmptyOrb(84.dp)
                            Spacer(Modifier.width(14.dp))
                            Text("Storage statistics are unavailable.", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        state.volumes.forEachIndexed { index, volume ->
                            VolumeRow(volume)
                            if (index != state.volumes.lastIndex) {
                                Spacer(Modifier.height(14.dp))
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
                                Spacer(Modifier.height(14.dp))
                            }
                        }
                    }
                }
            }

            item {
                SectionLabel("Recovery modes")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeTile(
                        modifier = Modifier.weight(1f),
                        kind = MediaKind.IMAGE,
                        title = "Deep scan",
                        caption = "Trash & hidden",
                        onClick = { vm.startDeepScan(false) },
                    )
                    ModeTile(
                        modifier = Modifier.weight(1f),
                        kind = MediaKind.VIDEO,
                        title = "Carve image",
                        caption = "Raw .img / .dd",
                        onClick = { vm.openCarve() },
                    )
                    ModeTile(
                        modifier = Modifier.weight(1f),
                        kind = MediaKind.DOCUMENT,
                        title = "Root raw",
                        caption = if (state.rootStatus?.rooted == true) "Root ready" else "Needs root",
                        onClick = { vm.openRoot() },
                    )
                }
            }

            item {
                GlassCard(padding = 16.dp) {
                    Row {
                        Metric(Fmt.count(state.foundCount), "found", Modifier.weight(1f), accent.a)
                        Metric(Fmt.count(state.recovered.size), "in vault", Modifier.weight(1f), accent.b)
                        Metric(Signatures.ALL.size.toString(), "formats", Modifier.weight(1f), accent.c)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(accent.a)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            if (state.scanning) "Engine is scanning storage right now" else "All processing stays on this device",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                            color = TextSecondary,
                        )
                    }
                }
            }

            if (state.results.isNotEmpty()) {
                item {
                    SectionLabel("Recovery insights")
                    Spacer(Modifier.height(8.dp))
                    GlassCard(padding = 16.dp) {
                        Row {
                            Metric(Fmt.count(state.strongCount), "strong", Modifier.weight(1f), accent.a)
                            Metric(Fmt.count(state.duplicateCount), "duplicates", Modifier.weight(1f), accent.b)
                            Metric(Fmt.bytes(state.vaultBytes), "in vault", Modifier.weight(1f), accent.c)
                        }
                        Spacer(Modifier.height(11.dp))
                        Text(
                            "Strong files have verified headers and sizes. Duplicates share a name, format and size.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.5.sp, lineHeight = 15.sp),
                            color = TextSecondary,
                        )
                    }
                }
            }

            if (state.history.isNotEmpty()) {
                item {
                    SectionLabel("Recent activity") {
                        Text(
                            "${state.history.size} runs",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    GlassCard(padding = 8.dp) {
                        state.history.take(3).forEachIndexed { index, entry ->
                            ActivityRow(entry)
                            if (index != state.history.take(3).lastIndex) {
                                Spacer(Modifier.height(6.dp))
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            if (state.results.isNotEmpty()) {
                item {
                    SectionLabel("Latest discoveries") {
                        Text(
                            "View all",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent.a,
                            modifier = Modifier.pressClickable { vm.goTab(Screen.RESULTS) },
                        )
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(latest, key = { it.id }) { file ->
                            RecentCard(file) { vm.showPreview(file) }
                        }
                    }
                }
            }

            item {
                GlassCard(padding = 16.dp) {
                    FeatureRow(MediaKind.ARCHIVE, "System trash & LOST.DIR", "Recycle bins, .trashed folders, fragments")
                    Spacer(Modifier.height(13.dp))
                    FeatureRow(MediaKind.VIDEO, "Signature carving", "Rebuilds files from their magic bytes")
                    Spacer(Modifier.height(13.dp))
                    FeatureRow(MediaKind.DOCUMENT, "Private by design", "Zero uploads, no accounts, no tracking")
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        BottomNav(current = Screen.HOME, onSelect = vm::goTab)
    }
}

@Composable
private fun VolumeRow(volume: StorageVolume) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        StorageRing(volume.usedPercent, 72.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(volume.label, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(3.dp))
            Text(
                "${Fmt.bytes(volume.free)} free of ${Fmt.bytes(volume.total)}",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp),
                color = TextSecondary,
            )
            Spacer(Modifier.height(9.dp))
            SegmentedBar(volume.usedPercent, volume.used, volume.total)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${volume.usedPercent}%",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
                color = accent.a,
            )
            Text("used", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

@Composable
private fun StorageRing(percent: Int, size: androidx.compose.ui.unit.Dp) {
    val accent = LocalAccent.current
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.06f), style = Stroke(stroke), radius = this.size.minDimension / 2 - stroke)
            drawArc(
                brush = Brush.sweepGradient(listOf(accent.a, accent.b, accent.c, accent.a)),
                startAngle = -90f,
                sweepAngle = 360f * (percent.coerceIn(0, 100) / 100f),
                useCenter = false,
                topLeft = Offset(stroke, stroke),
                size = Size(this.size.width - stroke * 2, this.size.height - stroke * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            "$percent%",
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = TextPrimary,
        )
    }
}

@Composable
private fun SegmentedBar(percent: Int, used: Long, total: Long) {
    val accent = LocalAccent.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.06f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(accent.a, accent.b, accent.c))),
        )
    }
}

@Composable
private fun ModeTile(
    modifier: Modifier,
    kind: MediaKind,
    title: String,
    caption: String,
    onClick: () -> Unit,
) {
    GlassCard(modifier = modifier, padding = 13.dp, corner = 18.dp, onClick = onClick) {
        KindBadge(kind, 38.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.5.sp),
            color = TextPrimary,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.2.sp),
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RecentCard(file: com.phoenix.recover.engine.RecoveredFile, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.width(126.dp), padding = 10.dp, corner = 18.dp, onClick = onClick) {
        FileThumb(file, 106.dp, Modifier.fillMaxWidth())
        Spacer(Modifier.height(9.dp))
        Text(
            file.name,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.5.sp),
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            Fmt.bytes(file.size),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = TextSecondary,
        )
    }
}

@Composable
private fun ActivityRow(entry: ActivityEntry) {
    val kind = when (entry.type) {
        ActivityType.SCAN, ActivityType.TRASH -> MediaKind.DOCUMENT
        ActivityType.CARVE -> MediaKind.IMAGE
        ActivityType.ROOT -> MediaKind.ARCHIVE
    }
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadge(kind, 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.type.label + if (entry.cancelled) " · stopped" else "",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.5.sp),
                color = TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${Fmt.count(entry.found)} found · ${Fmt.duration(entry.elapsedMs)} · ${Fmt.relativeTime(entry.timestamp)}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                color = TextSecondary,
            )
            if (entry.detail.isNotBlank()) {
                Text(
                    entry.detail,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = TextSecondary.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FeatureRow(kind: MediaKind, title: String, subtitle: String) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(accent.a.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            KindGlyph(kind, Modifier.size(19.dp), tint = accent.a, strokeWidth = 1.4.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.sp), color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.5.sp), color = TextSecondary)
        }
    }
}
