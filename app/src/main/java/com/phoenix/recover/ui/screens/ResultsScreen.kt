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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phoenix.recover.RecoverViewModel
import com.phoenix.recover.engine.MediaKind
import com.phoenix.recover.engine.RecoveredFile
import com.phoenix.recover.ui.Screen
import com.phoenix.recover.ui.SortMode
import com.phoenix.recover.ui.UiState
import com.phoenix.recover.ui.components.BottomNav
import com.phoenix.recover.ui.components.ConfidenceRing
import com.phoenix.recover.ui.components.EmptyOrb
import com.phoenix.recover.ui.components.FileThumb
import com.phoenix.recover.ui.components.GhostButton
import com.phoenix.recover.ui.components.GlassCard
import com.phoenix.recover.ui.components.GradientButton
import com.phoenix.recover.ui.components.PillChip
import com.phoenix.recover.ui.components.PulseDot
import com.phoenix.recover.ui.components.SectionLabel
import com.phoenix.recover.ui.components.SelectDot
import com.phoenix.recover.ui.components.TopBar
import com.phoenix.recover.ui.components.pressClickable
import com.phoenix.recover.ui.theme.LocalAccent
import com.phoenix.recover.ui.theme.TextPrimary
import com.phoenix.recover.ui.theme.TextSecondary
import com.phoenix.recover.ui.theme.Warn
import com.phoenix.recover.util.Fmt

@Composable
fun ResultsScreen(state: UiState, vm: RecoverViewModel) {
    val accent = LocalAccent.current
    val visible = remember(state.results, state.filter, state.query, state.sort) {
        filteredResults(state)
    }
    val visibleBytes = remember(visible) { visible.sumOf { it.size } }
    val selected = remember(state.results, state.selectedIds) {
        state.results.filter { it.id in state.selectedIds }
    }
    val selectedBytes = remember(selected) { selected.sumOf { it.size } }
    val counts = remember(state.results) { state.results.groupingBy { it.kind }.eachCount() }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "Recovered results",
            subtitle = "${Fmt.count(visible.size)} shown · ${Fmt.bytes(visibleBytes)}",
            trailing = {
                if (visible.isNotEmpty()) {
                    Text(
                        if (state.selectedIds.isEmpty()) "Select all" else "Clear",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent.a,
                        modifier = Modifier.pressClickable {
                            if (state.selectedIds.isEmpty()) vm.selectAll(visible.map { it.id })
                            else vm.clearSelection()
                        },
                    )
                }
            },
        )

        SearchRow(state.query, vm::setQuery)

        LazyRow(
            Modifier.padding(top = 10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                PillChip(
                    label = "All",
                    selected = state.filter == null,
                    count = state.results.size,
                    onClick = { vm.setFilter(null) },
                )
            }
            items(MediaKind.entries.toList(), key = { it.name }) { kind ->
                val count = counts[kind] ?: 0
                if (count > 0) {
                    PillChip(
                        label = kind.label,
                        selected = state.filter == kind,
                        count = count,
                        onClick = { vm.setFilter(if (state.filter == kind) null else kind) },
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "SORT",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, letterSpacing = 1.4.sp),
                color = TextSecondary,
            )
            Spacer(Modifier.width(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(SortMode.entries.toList(), key = { it.name }) { mode ->
                    val active = state.sort == mode
                    Text(
                        mode.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (active) accent.a else TextSecondary.copy(alpha = 0.65f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (active) accent.a.copy(alpha = 0.12f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (active) accent.a.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f),
                                RoundedCornerShape(50),
                            )
                            .pressClickable { vm.setSort(mode) }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
            }
        }

        if (state.recovering) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(accent.a.copy(alpha = 0.08f))
                    .padding(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulseDot(accent.a)
                Spacer(Modifier.width(9.dp))
                Text(
                    state.recoverText.ifBlank { "Recovering…" },
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary,
                )
            }
        }

        if (visible.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                    EmptyOrb(140.dp)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (state.query.isNotBlank() || state.filter != null) "No matches" else "Nothing recovered yet",
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 19.sp),
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (state.query.isNotBlank() || state.filter != null) {
                            "Try clearing the search or the file-type filter, or run another scan."
                        } else {
                            "Run a deep scan or carve a raw image to find deleted files."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    GradientButton("Go to Home", Modifier.width(190.dp)) { vm.goTab(Screen.HOME) }
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(visible, key = { it.id }) { file ->
                    ResultRow(
                        file = file,
                        selected = file.id in state.selectedIds,
                        onToggle = { vm.toggleSelect(file.id) },
                        onOpen = { vm.showPreview(file) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }

        if (selected.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xF20E1524))
                    .border(1.dp, accent.a.copy(alpha = 0.24f), RoundedCornerShape(18.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectDot(selected = true, size = 26.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${selected.size} selected",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.5.sp),
                        color = TextPrimary,
                    )
                    Text(Fmt.bytes(selectedBytes), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                GhostButton("Clear", Modifier.width(78.dp)) { vm.clearSelection() }
                Spacer(Modifier.width(8.dp))
                GradientButton(
                    "Recover",
                    Modifier.width(116.dp),
                    enabled = !state.recovering,
                    onClick = { vm.recoverSelected() },
                )
            }
        }

        BottomNav(current = Screen.RESULTS, onSelect = vm::goTab)
    }
}

private fun filteredResults(state: UiState): List<RecoveredFile> {
    val filtered = state.results.filter { file ->
        (state.filter == null || file.kind == state.filter) &&
            (state.query.isBlank() || file.name.contains(state.query, ignoreCase = true) ||
                file.format.contains(state.query, ignoreCase = true) ||
                (file.sourcePath ?: "").contains(state.query, ignoreCase = true))
    }
    return when (state.sort) {
        SortMode.CONFIDENCE -> filtered.sortedByDescending { it.confidence }
        SortMode.SIZE -> filtered.sortedByDescending { it.size }
        SortMode.NAME -> filtered.sortedBy { it.name.lowercase() }
        SortMode.ORIGIN -> filtered.sortedBy { it.origin.label }
    }
}

@Composable
private fun SearchRow(query: String, onChange: (String) -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(9.dp))
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            cursorBrush = SolidColor(accent.a),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary, fontSize = 13.sp),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Search files, formats or folders…", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp), color = TextSecondary.copy(alpha = 0.7f))
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            Text(
                "×",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp),
                color = TextSecondary,
                modifier = Modifier.pressClickable { onChange("") }.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ResultRow(
    file: RecoveredFile,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(19.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) {
                    Brush.horizontalGradient(listOf(accent.a.copy(alpha = 0.13f), Color.White.copy(alpha = 0.03f)))
                } else {
                    Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.055f), Color.White.copy(alpha = 0.02f)))
                },
            )
            .border(1.dp, if (selected) accent.a.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.06f), shape)
            .pressClickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.pressClickable(onClick = onToggle)) {
            SelectDot(selected = selected, size = 26.dp)
        }
        Spacer(Modifier.width(10.dp))
        FileThumb(file, 52.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.5.sp),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(Fmt.bytes(file.size))
                    if (file.format.isNotBlank()) append(" · ").append(file.format)
                    append(" · ").append(file.origin.label)
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file.partial) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "Partial fragment",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                    color = Warn,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        ConfidenceRing(file.confidence, 40.dp)
    }
}

@Composable
fun PreviewDialog(file: RecoveredFile, state: UiState, vm: RecoverViewModel) {
    val accent = LocalAccent.current
    Dialog(onDismissRequest = { vm.dismissPreview() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
            GlassCard(padding = 20.dp, corner = 26.dp) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Recovered file") {
                            Text(
                                "×",
                                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 20.sp),
                                color = TextSecondary,
                                modifier = Modifier.pressClickable { vm.dismissPreview() }.padding(4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    FileThumb(file, 216.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        file.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp),
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${file.kind.label} · ${Fmt.bytes(file.size)}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.035f)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ConfidenceRing(file.confidence, 52.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Recovery confidence",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.sp),
                                color = TextSecondary,
                            )
                            Text(
                                when {
                                    file.confidence >= 82 -> "Strong — structure verified"
                                    file.confidence >= 55 -> "Likely — header and size valid"
                                    else -> "Fragment — partial structure"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.sp),
                                color = if (file.confidence >= 55) accent.a else Warn,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    DetailRow("Format", file.format.ifBlank { "Unknown" })
                    DetailRow("Source", file.origin.label)
                    DetailRow(
                        "Confidence",
                        when {
                            file.confidence >= 82 -> "Strong · ${file.confidence}%"
                            file.confidence >= 55 -> "Likely · ${file.confidence}%"
                            else -> "Fragment · ${file.confidence}%"
                        },
                    )
                    if (file.offset != null) DetailRow("Disk offset", "0x${file.offset.toString(16).uppercase()}")
                    val path = file.scratchPath ?: file.sourcePath
                    if (!path.isNullOrBlank()) DetailRow("Path", Fmt.shortPath(path, 56))
                    if (file.detail.isNotBlank()) DetailRow("Detail", file.detail)
                    Spacer(Modifier.height(18.dp))
                    GradientButton(
                        "Recover to Downloads",
                        Modifier.fillMaxWidth(),
                        enabled = !state.recovering,
                        onClick = { vm.recoverOne(file) },
                    )
                    Spacer(Modifier.height(9.dp))
                    GhostButton("Close", Modifier.fillMaxWidth()) { vm.dismissPreview() }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, letterSpacing = 1.sp),
            color = TextSecondary,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp),
            color = TextPrimary.copy(alpha = 0.9f),
            modifier = Modifier.weight(1f),
        )
    }
}
