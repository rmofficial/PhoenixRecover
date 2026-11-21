package com.phoenix.recover.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoenix.recover.R
import com.phoenix.recover.RecoverViewModel
import com.phoenix.recover.engine.MediaKind
import com.phoenix.recover.engine.Signatures
import com.phoenix.recover.ui.Screen
import com.phoenix.recover.ui.Settings
import com.phoenix.recover.ui.UiState
import com.phoenix.recover.ui.components.AccessPill
import com.phoenix.recover.ui.components.BottomNav
import com.phoenix.recover.ui.components.GlassCard
import com.phoenix.recover.ui.components.GradientButton
import com.phoenix.recover.ui.components.KindBadge
import com.phoenix.recover.ui.components.PhoenixMark
import com.phoenix.recover.ui.components.PulseDot
import com.phoenix.recover.ui.components.SectionLabel
import com.phoenix.recover.ui.components.TopBar
import com.phoenix.recover.ui.components.pressClickable
import com.phoenix.recover.ui.theme.Accents
import com.phoenix.recover.ui.theme.AccentPalette
import com.phoenix.recover.ui.theme.LocalAccent
import com.phoenix.recover.ui.theme.Success
import com.phoenix.recover.ui.theme.TextPrimary
import com.phoenix.recover.ui.theme.TextSecondary
import com.phoenix.recover.util.StorageUtil

@Composable
fun SettingsScreen(state: UiState, vm: RecoverViewModel) {
    val context = LocalContext.current
    val accent = LocalAccent.current
    val settings = state.settings

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "Settings",
            subtitle = "Engine, appearance & storage",
            onBack = { vm.back() },
        )
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SectionLabel("Accent")
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Accents.forEach { palette ->
                        AccentSwatch(
                            palette = palette,
                            selected = settings.accentKey == palette.key,
                            modifier = Modifier.weight(1f),
                            onClick = { vm.updateSettings(settings.copy(accentKey = palette.key)) },
                        )
                    }
                }
            }

            item {
                SectionLabel("Scanner")
                Spacer(Modifier.height(9.dp))
                GlassCard(padding = 15.dp) {
                    ToggleRow(
                        title = "Include hidden folders",
                        subtitle = "Detects `.nomedia`, dot folders and orphaned media.",
                        checked = settings.includeHidden,
                        onChange = { vm.updateSettings(settings.copy(includeHidden = it)) },
                    )
                    Divider()
                    ToggleRow(
                        title = "Find unindexed files",
                        subtitle = "Compares storage against the MediaStore index.",
                        checked = settings.detectUnindexed,
                        onChange = { vm.updateSettings(settings.copy(detectUnindexed = it)) },
                    )
                    Divider()
                    StepperRow(
                        title = "Scan depth",
                        subtitle = "How many folder levels are visited.",
                        value = "${settings.maxDepth}",
                        canMinus = settings.maxDepth > 4,
                        canPlus = settings.maxDepth < 32,
                        onMinus = { vm.updateSettings(settings.copy(maxDepth = settings.maxDepth - 1)) },
                        onPlus = { vm.updateSettings(settings.copy(maxDepth = settings.maxDepth + 1)) },
                    )
                    Divider()
                    StepperRow(
                        title = "Minimum file size",
                        subtitle = "Ignore fragments smaller than this.",
                        value = "${settings.minSizeKb} KB",
                        canMinus = settings.minSizeKb > 1,
                        canPlus = settings.minSizeKb < 256,
                        onMinus = { vm.updateSettings(settings.copy(minSizeKb = (settings.minSizeKb - 1).coerceAtLeast(1))) },
                        onPlus = { vm.updateSettings(settings.copy(minSizeKb = settings.minSizeKb + 1)) },
                    )
                }
            }

            item {
                SectionLabel("Carving")
                Spacer(Modifier.height(9.dp))
                GlassCard(padding = 15.dp) {
                    StepperRow(
                        title = "Max rebuilt file",
                        subtitle = "Upper bound for a single carved file.",
                        value = "${settings.maxCarveFileMb} MB",
                        canMinus = settings.maxCarveFileMb > 256,
                        canPlus = settings.maxCarveFileMb < 16384,
                        onMinus = { vm.updateSettings(settings.copy(maxCarveFileMb = (settings.maxCarveFileMb / 2).coerceAtLeast(256))) },
                        onPlus = { vm.updateSettings(settings.copy(maxCarveFileMb = (settings.maxCarveFileMb * 2).coerceAtMost(16384))) },
                    )
                    Divider()
                    StepperRow(
                        title = "Output budget",
                        subtitle = "Maximum staged bytes per carve run.",
                        value = if (settings.maxOutputMb >= 1024) "${settings.maxOutputMb / 1024} GB" else "${settings.maxOutputMb} MB",
                        canMinus = settings.maxOutputMb > 256,
                        canPlus = settings.maxOutputMb < 16384,
                        onMinus = { vm.updateSettings(settings.copy(maxOutputMb = (settings.maxOutputMb / 2).coerceAtLeast(256))) },
                        onPlus = { vm.updateSettings(settings.copy(maxOutputMb = (settings.maxOutputMb * 2).coerceAtMost(16384))) },
                    )
                }
            }

            item {
                SectionLabel("Access")
                Spacer(Modifier.height(9.dp))
                GlassCard(padding = 15.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Storage access", style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.sp), color = TextPrimary)
                            Text(
                                if (state.hasAccess) "Full access granted" else "Limited — hidden folders are skipped",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.5.sp),
                                color = if (state.hasAccess) Success else TextSecondary,
                            )
                        }
                        AccessPill(state.hasAccess)
                    }
                    Spacer(Modifier.height(12.dp))
                    GradientButton(
                        if (state.hasAccess) "Re-check access" else "Grant all-files access",
                        Modifier.fillMaxWidth(),
                    ) {
                        if (state.hasAccess) {
                            vm.refreshAccess()
                        } else {
                            runCatching { context.startActivity(StorageUtil.allFilesIntent(context)) }
                                .onFailure { vm.toast("Open Settings → Apps → Phoenix Recover") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            "Android 11+ requires the special All files access toggle."
                        } else {
                            "Storage read/write permission is used."
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = TextSecondary.copy(alpha = 0.7f),
                    )
                }
            }

            item {
                GlassCard(padding = 15.dp, onClick = { vm.openRoot() }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KindBadge(MediaKind.ARCHIVE, 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Raw partition mode", style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.sp), color = TextPrimary)
                            Text(
                                when {
                                    state.rootBusy -> "Working…"
                                    state.rootStatus?.rooted == true -> "Root shell detected · ready"
                                    state.rootStatus != null -> "Not rooted · guided setup"
                                    else -> "Image a partition for the deepest recovery"
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.5.sp),
                                color = TextSecondary,
                            )
                        }
                        Text("›", style = MaterialTheme.typography.headlineMedium.copy(fontSize = 20.sp), color = TextSecondary.copy(alpha = 0.5f))
                    }
                }
            }

            item {
                SectionLabel("About")
                Spacer(Modifier.height(9.dp))
                GlassCard(padding = 17.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PhoenixMark(44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Phoenix Recover", style = MaterialTheme.typography.titleLarge.copy(fontSize = 15.sp), color = TextPrimary)
                            Text(
                                "Version 1.2.0 · ${Signatures.ALL.size} formats · offline",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                color = TextSecondary,
                            )
                        }
                        Text(
                            "PRO",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 9.sp),
                            color = accent.a,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(accent.a.copy(alpha = 0.12f))
                                .border(1.dp, accent.a.copy(alpha = 0.3f), RoundedCornerShape(50))
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(13.dp))
                    Text(
                        "Recovery depends on free blocks not being overwritten. Deep scans and carving can only rebuild data still present on the storage — no app can guarantee resurrection of overwritten files.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.5.sp, lineHeight = 16.sp),
                        color = TextSecondary,
                    )
                }
            }

            item {
                SectionLabel("Developer")
                Spacer(Modifier.height(9.dp))
                DeveloperCard()
            }

            item {
                Text(
                    "Built for offline, on-device recovery.",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.4.sp),
                    color = TextSecondary.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        BottomNav(current = Screen.SETTINGS, onSelect = vm::goTab)
    }
}

@Composable
private fun AccentSwatch(
    palette: AccentPalette,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.025f))
            .border(
                1.dp,
                if (selected) palette.a.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.06f),
                RoundedCornerShape(16.dp),
            )
            .pressClickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(palette.a, palette.b, palette.c)))
                .border(2.dp, if (selected) Color.White.copy(alpha = 0.85f) else Color.Transparent, CircleShape),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            palette.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, letterSpacing = 0.2.sp),
            color = if (selected) palette.a else TextSecondary,
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.5.sp), color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp, lineHeight = 14.sp), color = TextSecondary)
        }
        GlassToggle(checked, onChange)
    }
}

@Composable
private fun GlassToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    val thumb by animateDpAsState(if (checked) 23.dp else 3.dp, label = "thumb")
    Box(
        Modifier
            .size(width = 46.dp, height = 27.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (checked) {
                    Brush.horizontalGradient(listOf(accent.a.copy(alpha = 0.85f), accent.b.copy(alpha = 0.85f)))
                } else {
                    Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.08f)))
                },
            )
            .pressClickable { onChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = thumb)
                .size(21.dp)
                .clip(CircleShape)
                .background(if (checked) Color(0xFF04121A) else TextSecondary.copy(alpha = 0.8f)),
        )
    }
}

@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    value: String,
    canMinus: Boolean,
    canPlus: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.5.sp), color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp, lineHeight = 14.sp), color = TextSecondary)
        }
        StepperButton("−", canMinus, onMinus)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(52.dp),
        )
        StepperButton("+", canPlus, onPlus)
    }
}

@Composable
private fun StepperButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
            .pressClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            color = if (enabled) accent.a else TextSecondary.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun Divider() {
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
    Spacer(Modifier.height(12.dp))
}

private enum class DevGlyph { EMAIL, PHONE, GITHUB, FACEBOOK }

@Composable
private fun DeveloperCard() {
    val context = LocalContext.current
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(24.dp)
    val email = "rahulmandal.official@zohomail.in"
    val phone = "7430852038"
    val github = "https://github.com/rmofficial"
    val facebook = "https://www.facebook.com/share/1FV6HZzVyd/"

    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        accent.a.copy(alpha = 0.15f),
                        Color.White.copy(alpha = 0.035f),
                        accent.b.copy(alpha = 0.12f),
                    ),
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        accent.a.copy(alpha = 0.70f),
                        Color.White.copy(alpha = 0.12f),
                        accent.b.copy(alpha = 0.60f),
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
                            listOf(accent.a.copy(alpha = 0.18f), Color.Transparent),
                            center = Offset(size.width * 0.12f, 0f),
                            radius = size.width * 0.9f,
                        ),
                    )
                },
        )
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeveloperAvatar()
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Rahul Mandal",
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(7.dp))
                        DevChip("CREATOR", accent.a)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "App Developer & Maintainer",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.5.sp),
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DevChip("FOUNDER", accent.c)
                        DevChip("VERIFIED", Success)
                    }
                }
            }
            Spacer(Modifier.height(15.dp))
            Text(
                "“Privacy-first recovery tools, engineered to keep every byte on your device.”",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.5.sp, lineHeight = 16.sp),
                color = TextSecondary,
            )
            Spacer(Modifier.height(13.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                accent.a.copy(alpha = 0.45f),
                                accent.b.copy(alpha = 0.45f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Spacer(Modifier.height(13.dp))
            Text(
                "CONTACT & SOCIAL",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, letterSpacing = 1.3.sp),
                color = TextSecondary.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(9.dp))
            DeveloperInfoRow(DevGlyph.EMAIL, "Email", email) {
                openDeveloperLink(context, Intent.ACTION_SENDTO, "mailto:$email")
            }
            Spacer(Modifier.height(8.dp))
            DeveloperInfoRow(DevGlyph.PHONE, "Phone", phone) {
                openDeveloperLink(context, Intent.ACTION_DIAL, "tel:$phone")
            }
            Spacer(Modifier.height(8.dp))
            DeveloperInfoRow(DevGlyph.GITHUB, "GitHub", "github.com/rmofficial") {
                openDeveloperLink(context, Intent.ACTION_VIEW, github)
            }
            Spacer(Modifier.height(8.dp))
            DeveloperInfoRow(DevGlyph.FACEBOOK, "Facebook", "Rahul Mandal") {
                openDeveloperLink(context, Intent.ACTION_VIEW, facebook)
            }
            Spacer(Modifier.height(13.dp))
            Text(
                "© 2026 Rahul Mandal · All rights reserved",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, letterSpacing = 0.4.sp),
                color = TextSecondary.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DeveloperAvatar() {
    val accent = LocalAccent.current
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                Brush.radialGradient(
                    listOf(accent.a.copy(alpha = 0.40f), Color.Transparent),
                    center = center,
                    radius = size.minDimension / 2,
                ),
                radius = size.minDimension / 2,
            )
        }
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Brush.sweepGradient(listOf(accent.a, accent.b, accent.c, accent.a)))
                .padding(2.5.dp)
                .clip(CircleShape)
                .background(Color(0xFF0A0E18)),
        ) {
            Image(
                painter = painterResource(R.drawable.developer_rahul),
                contentDescription = "Rahul Mandal",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF060A12))
                .border(1.dp, Success.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PulseDot(Success, 5.dp)
            Text(
                "ACTIVE",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp, letterSpacing = 0.7.sp),
                color = Success,
            )
        }
    }
}

@Composable
private fun DevChip(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 7.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.9.sp,
        ),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun DeveloperInfoRow(
    glyph: DevGlyph,
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(15.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), shape)
            .pressClickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Brush.linearGradient(listOf(accent.a.copy(alpha = 0.22f), accent.b.copy(alpha = 0.14f))))
                .border(1.dp, accent.a.copy(alpha = 0.25f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            DeveloperGlyph(glyph, Modifier.size(17.dp), accent.a)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, letterSpacing = 1.1.sp),
                color = TextSecondary.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(1.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "›",
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp),
            color = accent.a.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun DeveloperGlyph(kind: DevGlyph, modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = 1.7.dp.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            DevGlyph.EMAIL -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.12f, h * 0.24f),
                    size = Size(w * 0.76f, h * 0.52f),
                    cornerRadius = CornerRadius(w * 0.10f),
                    style = stroke,
                )
                drawLine(tint, Offset(w * 0.16f, h * 0.28f), Offset(w * 0.50f, h * 0.56f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.84f, h * 0.28f), Offset(w * 0.50f, h * 0.56f), sw, StrokeCap.Round)
            }
            DevGlyph.PHONE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.29f, h * 0.10f),
                    size = Size(w * 0.42f, h * 0.80f),
                    cornerRadius = CornerRadius(w * 0.11f),
                    style = stroke,
                )
                drawLine(tint, Offset(w * 0.43f, h * 0.20f), Offset(w * 0.57f, h * 0.20f), sw, StrokeCap.Round)
                drawCircle(tint, radius = w * 0.035f, center = Offset(w * 0.50f, h * 0.80f))
            }
            DevGlyph.GITHUB -> {
                drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.30f, h * 0.24f), style = stroke)
                drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.30f, h * 0.76f), style = stroke)
                drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.70f, h * 0.42f), style = stroke)
                drawLine(tint, Offset(w * 0.30f, h * 0.34f), Offset(w * 0.30f, h * 0.66f), sw, StrokeCap.Round)
                val branch = Path().apply {
                    moveTo(w * 0.40f, h * 0.24f)
                    lineTo(w * 0.56f, h * 0.24f)
                    cubicTo(w * 0.66f, h * 0.24f, w * 0.70f, h * 0.30f, w * 0.70f, h * 0.32f)
                }
                drawPath(branch, tint, style = stroke)
            }
            DevGlyph.FACEBOOK -> {
                drawLine(tint, Offset(w * 0.58f, h * 0.30f), Offset(w * 0.58f, h * 0.84f), sw, StrokeCap.Round)
                val hook = Path().apply {
                    moveTo(w * 0.58f, h * 0.36f)
                    cubicTo(w * 0.58f, h * 0.20f, w * 0.70f, h * 0.14f, w * 0.78f, h * 0.19f)
                }
                drawPath(hook, tint, style = stroke)
                drawLine(tint, Offset(w * 0.42f, h * 0.53f), Offset(w * 0.70f, h * 0.53f), sw, StrokeCap.Round)
            }
        }
    }
}

private fun openDeveloperLink(context: Context, action: String, uri: String) {
    runCatching { context.startActivity(Intent(action, Uri.parse(uri))) }
        .onFailure { Toast.makeText(context, "No app available for this link", Toast.LENGTH_SHORT).show() }
}
