package com.phoenix.recover.ui.screens

import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoenix.recover.RecoverViewModel
import com.phoenix.recover.engine.MediaKind
import com.phoenix.recover.ui.Screen
import com.phoenix.recover.ui.UiState
import com.phoenix.recover.ui.components.GhostButton
import com.phoenix.recover.ui.components.GlassCard
import com.phoenix.recover.ui.components.GradientButton
import com.phoenix.recover.ui.components.KindBadge
import com.phoenix.recover.ui.components.Metric
import com.phoenix.recover.ui.components.PillChip
import com.phoenix.recover.ui.components.RecoveryOrb
import com.phoenix.recover.ui.components.SectionLabel
import com.phoenix.recover.ui.components.TopBar
import com.phoenix.recover.ui.theme.LocalAccent
import com.phoenix.recover.ui.theme.TextPrimary
import com.phoenix.recover.ui.theme.TextSecondary
import com.phoenix.recover.ui.theme.Warn
import com.phoenix.recover.util.Fmt
import kotlinx.coroutines.delay

@Composable
fun CarveScreen(state: UiState, vm: RecoverViewModel) {
    val context = LocalContext.current
    val accent = LocalAccent.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: "disk_image.img"
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.startCarve(uri, name)
        }
    }
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.carving) {
        while (state.carving) {
            now = SystemClock.elapsedRealtime()
            delay(900)
        }
    }
    val pct = if (state.carveTotal > 0) ((state.carveProcessed.toDouble() / state.carveTotal) * 100).toInt().coerceIn(0, 100) else 0
    val elapsed = if (state.carving) (now - state.carveStartedAt).coerceAtLeast(1) else (state.lastCarve?.elapsedMs ?: 0)
    val speed = if (elapsed > 0) state.carveProcessed * 1000 / elapsed else 0

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "Signature carve",
            subtitle = if (state.carving) "Rebuilding files from ${state.carveSourceName}" else "Raw .img / .dd recovery",
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
                        active = state.carving,
                        progress = if (state.carving) state.carveProcessed.toFloat() / state.carveTotal.coerceAtLeast(1).toFloat() else null,
                        title = when {
                            state.carving -> "CARVING"
                            state.lastCarve != null -> "CARVE DONE"
                            else -> "READY"
                        },
                        value = if (state.carving || state.lastCarve != null) "$pct%" else "",
                        caption = if (state.carving) "SCANNED" else "RAW ENGINE",
                        size = 250.dp,
                        particles = 22,
                    )
                }
            }

            item {
                GlassCard(padding = 15.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.carveStatus,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.sp),
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.carving) {
                            Text(
                                "$pct%",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = accent.a,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.06f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(if (state.carving) pct / 100f else if (state.lastCarve != null) 1f else 0f)
                                .height(6.dp)
                                .background(Brush.horizontalGradient(listOf(accent.a, accent.b, accent.c))),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (state.carveSourceName.isNotEmpty()) {
                            "${Fmt.shortPath(state.carveSourceName, 40)} · ${Fmt.bytes(state.carveTotal)}"
                        } else {
                            "Pick a disk image or a raw partition dump to carve."
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = TextSecondary,
                    )
                }
            }

            if (state.carving || state.lastCarve != null) {
                item {
                    GlassCard(padding = 16.dp) {
                        Row {
                            Metric(Fmt.count(state.carveFound), "rebuilt", Modifier.weight(1f), accent.a)
                            Metric(Fmt.bytes(state.carveBytes), "written", Modifier.weight(1f), TextPrimary)
                            Metric(Fmt.speed(speed), "speed", Modifier.weight(1f), accent.b)
                            Metric(Fmt.duration(elapsed), "elapsed", Modifier.weight(1f), TextPrimary)
                        }
                    }
                }
            }

            if (!state.carving && state.lastCarve == null) {
                item {
                    GlassCard(padding = 17.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KindBadge(MediaKind.IMAGE, 46.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("No root required", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                Text(
                                    "Carve a `.img`/`.dd` file — from a root dump, an SD card image or a disk backup.",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp, lineHeight = 16.sp),
                                    color = TextSecondary,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Steps()
                    }
                }
            }

            if (!state.carving) {
                item {
                    SectionLabel("File types to rebuild") {
                        Text(
                            "${state.carveKinds.size}/${MediaKind.entries.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent.a,
                        )
                    }
                    Spacer(Modifier.height(9.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            PillChip(
                                label = "All",
                                selected = state.carveKinds.size == MediaKind.entries.size,
                                onClick = { vm.setCarveKinds(MediaKind.entries.toSet()) },
                            )
                        }
                        items(MediaKind.entries.toList(), key = { it.name }) { kind ->
                            PillChip(
                                label = kind.label,
                                selected = state.carveKinds.size == 1 && kind in state.carveKinds,
                                onClick = { vm.setCarveKinds(setOf(kind)) },
                            )
                        }
                    }
                }
            }

            item {
                Column {
                    if (state.carving) {
                        GhostButton("Stop carve", Modifier.fillMaxWidth()) { vm.cancelCarve() }
                    } else {
                        GradientButton(
                            text = if (state.lastCarve != null) "Carve another image" else "Choose raw image",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { picker.launch(arrayOf("*/*")) },
                        )
                        if (state.carveFound > 0) {
                            Spacer(Modifier.height(10.dp))
                            GhostButton("View ${Fmt.count(state.carveFound)} rebuilt files", Modifier.fillMaxWidth()) {
                                vm.goTab(Screen.RESULTS)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Warn.copy(alpha = 0.07f))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("i", color = Warn, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(9.dp))
                            Text(
                                "Carving works best on a raw disk image. Recovered fragments may be partial when the source blocks were reused.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp, lineHeight = 14.sp),
                                color = TextSecondary,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@Composable
private fun Steps() {
    val accent = LocalAccent.current
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        listOf(
            "Get a raw image (root dd or an SD backup)",
            "Pick it here — scanning never uploads it",
            "Recovered files land in Downloads",
        ).forEachIndexed { index, text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(21.dp).clip(RoundedCornerShape(7.dp)).background(accent.a.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = accent.a,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp),
                    color = TextSecondary,
                )
            }
        }
    }
}
