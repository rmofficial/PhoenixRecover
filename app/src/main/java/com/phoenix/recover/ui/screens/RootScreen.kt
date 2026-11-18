package com.phoenix.recover.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.phoenix.recover.ui.components.PulseDot
import com.phoenix.recover.ui.components.RecoveryOrb
import com.phoenix.recover.ui.components.SectionLabel
import com.phoenix.recover.ui.components.TopBar
import com.phoenix.recover.ui.components.pressClickable
import com.phoenix.recover.ui.theme.LocalAccent
import com.phoenix.recover.ui.theme.Success
import com.phoenix.recover.ui.theme.TextPrimary
import com.phoenix.recover.ui.theme.TextSecondary
import com.phoenix.recover.ui.theme.Warn
import com.phoenix.recover.util.BlockDevice

@Composable
fun RootScreen(state: UiState, vm: RecoverViewModel) {
    val accent = LocalAccent.current
    var selectedDevice by remember { mutableStateOf<BlockDevice?>(null) }
    var sizeMb by remember { mutableStateOf(512) }

    LaunchedEffect(state.rootDevices) {
        if (selectedDevice == null) selectedDevice = state.rootDevices.firstOrNull()
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "Raw partition",
            subtitle = "Deepest recovery · read-only imaging",
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
                        active = state.rootBusy || state.rootStatus == null,
                        title = when {
                            state.rootBusy -> "IMAGING"
                            state.rootStatus?.rooted == true -> "ROOTED"
                            state.rootStatus != null -> "NO ROOT"
                            else -> "PROBING"
                        },
                        value = if (state.rootStatus?.rooted == true) "ROOT" else "",
                        caption = "RAW MODE",
                        size = 220.dp,
                        particles = 18,
                    )
                }
            }

            if (state.rootBusy) {
                item {
                    GlassCard(padding = 16.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PulseDot(accent.a)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                state.rootProgress.ifBlank { "Working…" },
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.5.sp),
                                color = TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        GhostButton("Cancel", Modifier.fillMaxWidth()) { vm.cancelRoot() }
                    }
                }
            }

            when {
                state.rootStatus == null -> {
                    item {
                        GlassCard(padding = 16.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PulseDot(accent.a)
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    "Looking for a root shell…",
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.5.sp),
                                    color = TextPrimary,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Phoenix asks `su` for uid=0. Nothing is modified — this screen only reads.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.5.sp),
                                color = TextSecondary,
                            )
                        }
                    }
                }

                state.rootStatus?.rooted == false -> {
                    item {
                        GlassCard(padding = 17.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Warn.copy(alpha = 0.13f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("!", color = Warn, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("This device isn't rooted", style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp), color = TextPrimary)
                                    Text(
                                        state.rootStatus?.detail ?: "Raw partition access needs root.",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.5.sp, lineHeight = 15.sp),
                                        color = TextSecondary,
                                    )
                                }
                            }
                            Spacer(Modifier.height(15.dp))
                            Text(
                                "You can still recover a lot without root",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.sp),
                                color = accent.a,
                            )
                            Spacer(Modifier.height(9.dp))
                            GuidanceRow("1", "Deep scan trash bins, LOST.DIR and hidden media — no root needed.")
                            Spacer(Modifier.height(9.dp))
                            GuidanceRow("2", "Carve a `.img` or SD-card image made on a computer.")
                            Spacer(Modifier.height(9.dp))
                            GuidanceRow("3", "Advanced: root the device, then image a partition here.")
                            Spacer(Modifier.height(16.dp))
                            GradientButton("Deep scan instead", Modifier.fillMaxWidth()) { vm.startDeepScan(false) }
                            Spacer(Modifier.height(9.dp))
                            GhostButton("Try root probe again", Modifier.fillMaxWidth()) { vm.probeRoot() }
                        }
                    }
                    item {
                        GlassCard(padding = 15.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                KindBadge(MediaKind.IMAGE, 42.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Have a disk image?", style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.5.sp), color = TextPrimary)
                                    Text(
                                        "Carve it directly on the phone — no root or computer needed at this step.",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.5.sp),
                                        color = TextSecondary,
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            GradientButton("Open carve mode", Modifier.fillMaxWidth()) { vm.openCarve() }
                        }
                    }
                }

                else -> {
                    item {
                        GlassCard(padding = 15.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(Success))
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    state.rootStatus?.detail ?: "Root active",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                                    color = Success,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    item {
                        SectionLabel("Partitions") {
                            Text("${state.rootDevices.size} found", style = MaterialTheme.typography.labelSmall, color = accent.a)
                        }
                        Spacer(Modifier.height(9.dp))
                        if (state.rootDevices.isEmpty()) {
                            GlassCard(padding = 15.dp) {
                                Text(
                                    "Could not enumerate /dev/block/by-name. Enter a path manually is not supported yet — use a raw image instead.",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                                    color = TextSecondary,
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.rootDevices.forEach { device ->
                                    DeviceRow(
                                        device = device,
                                        selected = selectedDevice?.target == device.target,
                                        onClick = { selectedDevice = device },
                                    )
                                }
                            }
                        }
                    }

                    item {
                        SectionLabel("Read size")
                        Spacer(Modifier.height(9.dp))
                        GlassCard(padding = 15.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (sizeMb >= 1024) "${sizeMb / 1024} GB" else "$sizeMb MB",
                                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                                        color = TextPrimary,
                                    )
                                    Text(
                                        "Only this many bytes are read from the start of the partition.",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp),
                                        color = TextSecondary,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    MiniStep("+128", sizeMb < 16384) { sizeMb = (sizeMb + 128).coerceAtMost(16384) }
                                    Spacer(Modifier.height(6.dp))
                                    MiniStep("−128", sizeMb > 128) { sizeMb = (sizeMb - 128).coerceAtLeast(128) }
                                }
                            }
                            Spacer(Modifier.height(13.dp))
                            GradientButton(
                                "Create image & carve",
                                Modifier.fillMaxWidth(),
                                enabled = selectedDevice != null && !state.rootBusy,
                                onClick = {
                                    selectedDevice?.let { vm.createRawImage(it, sizeMb) }
                                },
                            )
                        }
                    }

                    item {
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
                                "Imaging is read-only, but copying large partitions takes time and storage. Interrupting is safe.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp, lineHeight = 14.sp),
                                color = TextSecondary,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun DeviceRow(device: BlockDevice, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(15.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) accent.a.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.035f))
            .border(1.dp, if (selected) accent.a.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.06f), shape)
            .pressClickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape)
                .background(if (selected) accent.a else Color.Transparent)
                .border(1.5.dp, if (selected) accent.a else TextSecondary.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF04121A)))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.sp), color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(device.target, style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp), color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (device.readable) {
            Text("readable", style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp), color = Success)
        }
    }
}

@Composable
private fun GuidanceRow(index: String, text: String) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(accent.a.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(index, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold), color = accent.a)
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = TextSecondary,
        )
    }
}

@Composable
private fun MiniStep(label: String, enabled: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Text(
        label,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
        color = if (enabled) accent.a else TextSecondary.copy(alpha = 0.35f),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
            .pressClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
