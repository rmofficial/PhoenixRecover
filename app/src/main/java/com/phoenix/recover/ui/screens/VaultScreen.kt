package com.phoenix.recover.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoenix.recover.RecoverViewModel
import com.phoenix.recover.engine.MediaKind
import com.phoenix.recover.ui.Screen
import com.phoenix.recover.ui.UiState
import com.phoenix.recover.ui.components.BottomNav
import com.phoenix.recover.ui.components.EmptyOrb
import com.phoenix.recover.ui.components.GlassCard
import com.phoenix.recover.ui.components.GradientButton
import com.phoenix.recover.ui.components.KindBadge
import com.phoenix.recover.ui.components.PillChip
import com.phoenix.recover.ui.components.TopBar
import com.phoenix.recover.ui.components.pressClickable
import com.phoenix.recover.ui.theme.LocalAccent
import com.phoenix.recover.ui.theme.TextPrimary
import com.phoenix.recover.ui.theme.TextSecondary
import com.phoenix.recover.util.Fmt
import com.phoenix.recover.util.RecoverySaver
import com.phoenix.recover.util.SavedItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VaultScreen(state: UiState, vm: RecoverViewModel) {
    val context = LocalContext.current
    val accent = LocalAccent.current
    val items = state.recovered.sortedByDescending { it.savedAt }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "Vault",
            subtitle = if (items.isEmpty()) "Recovered files appear here" else "${Fmt.count(items.size)} files · ${Fmt.bytes(items.sumOf { it.size })}",
        )

        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                    EmptyOrb(150.dp)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Your vault is empty",
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 20.sp),
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Recovered files are copied to Downloads/Phoenix Recover and listed here for quick access.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(18.dp))
                    GradientButton("Find deleted files", Modifier.width(200.dp)) { vm.startDeepScan(false) }
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    GlassCard(padding = 15.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(accent.a.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("↓", color = accent.a, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Downloads/Phoenix Recover", style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.5.sp), color = TextPrimary)
                                Text(
                                    "Copies stay on this device. Delete them any time from Files.",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.5.sp),
                                    color = TextSecondary,
                                )
                            }
                        }
                    }
                }
                items(items, key = { it.id }) { item ->
                    VaultRow(
                        item = item,
                        onOpen = {
                            val intent = RecoverySaver.openIntent(context, item)
                            if (intent != null) {
                                runCatching { context.startActivity(intent) }
                                    .onFailure { vm.toast("No app can open this file") }
                            } else {
                                vm.toast("File is no longer available")
                            }
                        },
                        onShare = {
                            val intent = RecoverySaver.shareIntent(context, item)
                            if (intent != null) {
                                runCatching { context.startActivity(Intent.createChooser(intent, "Share recovered file")) }
                                    .onFailure { vm.toast("Sharing failed") }
                            } else {
                                vm.toast("File is no longer available")
                            }
                        },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }

        BottomNav(current = Screen.VAULT, onSelect = vm::goTab)
    }
}

@Composable
private fun VaultRow(item: SavedItem, onOpen: () -> Unit, onShare: () -> Unit) {
    val accent = LocalAccent.current
    GlassCard(padding = 11.dp, corner = 18.dp, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindBadge(item.kind, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.5.sp),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${Fmt.bytes(item.size)} · ${dateLabel(item.savedAt)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                    color = TextSecondary,
                )
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniAction("Open", accent.a, onOpen)
                    MiniAction("Share", TextSecondary, onShare)
                }
            }
            Text(
                "›",
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 20.sp),
                color = TextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun MiniAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
            .pressClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun dateLabel(ms: Long): String {
    if (ms <= 0) return "recently"
    val now = System.currentTimeMillis()
    val dayStart = now - (now % (24 * 60 * 60 * 1000))
    return when {
        ms >= dayStart -> "Today " + SimpleDateFormat("h:mm a", Locale.US).format(Date(ms))
        ms >= dayStart - 86_400_000 -> "Yesterday"
        else -> SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(ms))
    }
}
