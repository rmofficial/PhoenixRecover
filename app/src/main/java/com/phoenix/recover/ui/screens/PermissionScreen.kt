package com.phoenix.recover.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.phoenix.recover.RecoverViewModel
import com.phoenix.recover.ui.components.GhostButton
import com.phoenix.recover.ui.components.GradientButton
import com.phoenix.recover.ui.components.GradientText
import com.phoenix.recover.ui.components.PhoenixMark
import com.phoenix.recover.ui.components.ProChip
import com.phoenix.recover.ui.components.RecoveryOrb
import com.phoenix.recover.ui.components.SectionLabel
import com.phoenix.recover.ui.theme.LocalAccent
import com.phoenix.recover.ui.theme.Success
import com.phoenix.recover.ui.theme.TextPrimary
import com.phoenix.recover.ui.theme.TextSecondary
import com.phoenix.recover.util.StorageUtil

@Composable
fun PermissionScreen(vm: RecoverViewModel) {
    val context = LocalContext.current
    val accent = LocalAccent.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshAccess()
    }
    fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val attempts = listOf(
                runCatching { StorageUtil.allFilesIntent(context) }.getOrNull(),
                runCatching { Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) }.getOrNull(),
                runCatching {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                }.getOrNull(),
            ).filterNotNull()
            var launched = false
            for (intent in attempts) {
                val ok = runCatching { context.startActivity(intent) }.isSuccess
                if (ok) {
                    launched = true
                    break
                }
            }
            if (!launched) vm.toast("Open Settings → Apps → Phoenix Recover → Permissions")
        } else {
            launcher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhoenixMark(30.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "PHOENIX RECOVER",
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.6.sp, fontSize = 11.sp),
                color = TextPrimary,
            )
            Spacer(Modifier.width(8.dp))
            ProChip()
        }
        Spacer(Modifier.height(18.dp))
        Box(contentAlignment = Alignment.Center) {
            RecoveryOrb(active = true, size = 210.dp, title = "", value = "", particles = 16)
            PhoenixMark(92.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "One permission.",
            style = MaterialTheme.typography.displayMedium.copy(fontSize = 33.sp, lineHeight = 38.sp),
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            GradientText(
                "Real recovery.",
                MaterialTheme.typography.displayMedium.copy(fontSize = 33.sp, lineHeight = 40.sp),
            )
        }
        Text(
            "Android hides deleted and orphaned files from normal apps. Full storage access lets Phoenix scan trash bins, hidden folders and raw signatures — everything stays on your phone.",
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp, fontSize = 13.sp),
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
        )
        Spacer(Modifier.height(10.dp))
    fun exploreLimited() {
        val missing = StorageUtil.mediaPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            launcher.launch(missing.toTypedArray())
        }
        vm.dismissPermission()
    }

    Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White.copy(alpha = 0.045f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            SectionLabel("What you unlock")
            Benefit("Scan system trash, .trashed, LOST.DIR and recycle bins")
            Benefit("Find hidden media and files missing from the media index")
            Benefit("Recover photos, videos, audio, documents and archives")
            Benefit("Nothing leaves the device — no account, no cloud")
        }
        Spacer(Modifier.height(18.dp))
        GradientButton("Grant all-files access", Modifier.fillMaxWidth(), onClick = { requestAccess() })
        Spacer(Modifier.height(10.dp))
        GhostButton("Explore limited mode", Modifier.fillMaxWidth()) { exploreLimited() }
        Spacer(Modifier.height(10.dp))
        Text(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "Android 11+ · All files access (MANAGE_EXTERNAL_STORAGE)"
            } else {
                "Storage permission · read/write"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.2.sp),
            color = TextSecondary.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Benefit(text: String) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(accent.a.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Success, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 17.sp),
            color = TextPrimary.copy(alpha = 0.9f),
            fontWeight = FontWeight.Normal,
        )
    }
}
