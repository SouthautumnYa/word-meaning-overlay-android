package com.codex.wordoverlay

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var settings: AppSettings
    private var overlayGranted by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)
    private var translationEnabled by mutableStateOf(true)
    private var diagnosticStatus by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tuneSystemBars()
        settings = AppSettings(this)
        updateUiState()

        setContent {
            WordOverlayTheme {
                LaunchedEffect(Unit) {
                    while (true) {
                        updateUiState()
                        delay(DIAGNOSTIC_REFRESH_MILLIS)
                    }
                }

                MainScreen(
                    dismissSeconds = settings.getDismissSeconds(),
                    translationEnabled = translationEnabled,
                    overlayGranted = overlayGranted,
                    accessibilityEnabled = accessibilityEnabled,
                    diagnosticStatus = diagnosticStatus,
                    onSaveDuration = { settings.setDismissSeconds(it) },
                    onRequestOverlay = { requestOverlayPermission() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onEnableTranslation = { enableTranslation() },
                    onPauseTranslation = { pauseTranslation() },
                    onRefreshDiagnostics = { updateUiState() },
                    onTestOverlay = { testHelloOverlay() }
                )
            }
        }

        startMonitorServiceIfReady()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
        startMonitorServiceIfReady()
    }

    private fun updateUiState() {
        overlayGranted = Settings.canDrawOverlays(this)
        accessibilityEnabled = isWordAccessibilityServiceEnabled()
        translationEnabled = settings.isTranslationEnabled()
        diagnosticStatus = Diagnostics.latest(this)
    }

    private fun tuneSystemBars() {
        window.statusBarColor = AndroidColor.rgb(248, 239, 224)
        window.navigationBarColor = AndroidColor.rgb(255, 253, 248)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri()
        )
        startActivity(intent)
    }

    private fun enableTranslation() {
        settings.setTranslationEnabled(true)
        Diagnostics.record(this, "Translation enabled by user.")
        startMonitorService()
        updateUiState()
    }

    private fun pauseTranslation() {
        settings.setTranslationEnabled(false)
        Diagnostics.record(this, "Translation paused by user.")
        startService(ClipboardMonitorService.createStopIntent(this))
        CoroutineScope(Dispatchers.IO).launch {
            KeepAliveManager(applicationContext).stopRootWatchdog()
        }
        updateUiState()
    }

    private fun startMonitorService() {
        if (!settings.isTranslationEnabled()) return
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        val intent = Intent(this, ClipboardMonitorService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startMonitorServiceIfReady() {
        if (settings.isTranslationEnabled() && Settings.canDrawOverlays(this)) {
            startMonitorService()
        }
    }

    private fun testHelloOverlay() {
        if (!settings.isTranslationEnabled()) {
            Diagnostics.record(this, "Test ignored because translation is paused.")
            updateUiState()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        Diagnostics.record(this, "Test hello overlay requested.")
        startService(ProcessTextLookupService.createIntent(this, "hello"))
        updateUiState()
    }

    private fun isWordAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val componentName = ComponentName(this, WordAccessibilityService::class.java)
        val expectedNames = setOf(
            componentName.flattenToString(),
            componentName.flattenToShortString()
        )

        return enabledServices
            .split(':')
            .any { enabledName -> expectedNames.any { it.equals(enabledName, ignoreCase = true) } }
    }

    companion object {
        private const val DIAGNOSTIC_REFRESH_MILLIS = 1_000L
    }
}

@Composable
private fun WordOverlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = InkGreen,
            onPrimary = Color.White,
            secondary = Copper,
            tertiary = SeaGlass,
            background = Paper,
            surface = Color.White,
            onSurface = Ink
        ),
        typography = MaterialTheme.typography,
        content = content
    )
}

@Composable
private fun MainScreen(
    dismissSeconds: Int,
    translationEnabled: Boolean,
    overlayGranted: Boolean,
    accessibilityEnabled: Boolean,
    diagnosticStatus: String,
    onSaveDuration: (Int) -> Unit,
    onRequestOverlay: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onEnableTranslation: () -> Unit,
    onPauseTranslation: () -> Unit,
    onRefreshDiagnostics: () -> Unit,
    onTestOverlay: () -> Unit
) {
    var input by remember(dismissSeconds) { mutableStateOf(dismissSeconds.toString()) }
    val rootKeepAliveHealthy = translationEnabled &&
        !diagnosticStatus.contains("Root keep-alive failed", ignoreCase = true) &&
        !diagnosticStatus.contains("Root keep-alive stopped", ignoreCase = true)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFF8EFE0), Color(0xFFE9F4F0), Color(0xFFFFFDF8))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HeroCard(
                dismissSeconds = dismissSeconds,
                translationEnabled = translationEnabled,
                overlayGranted = overlayGranted,
                accessibilityEnabled = accessibilityEnabled,
                rootKeepAliveHealthy = rootKeepAliveHealthy
            )

            ControlCard(
                translationEnabled = translationEnabled,
                onEnableTranslation = onEnableTranslation,
                onPauseTranslation = onPauseTranslation
            )

            PermissionCard(
                title = "悬浮窗权限",
                body = if (overlayGranted) {
                    "已允许在屏幕右上角显示释义卡片。"
                } else {
                    "需要开启悬浮窗权限，复制单词后才能弹出解释。"
                },
                statusText = if (overlayGranted) "已开启" else "待授权",
                statusColor = if (overlayGranted) Healthy else Warning,
                buttonText = if (overlayGranted) "查看权限" else "开启权限",
                onClick = onRequestOverlay
            )

            PermissionCard(
                title = "辅助功能触发",
                body = if (accessibilityEnabled) {
                    "已开启。总开关开启时，复制或选中英文单词会更快触发查词。"
                } else {
                    "建议开启辅助功能服务，后台复制时更稳定。"
                },
                statusText = if (accessibilityEnabled) "已开启" else "建议开启",
                statusColor = if (accessibilityEnabled) Healthy else Copper,
                buttonText = "打开辅助功能设置",
                onClick = onOpenAccessibilitySettings
            )

            DurationCard(
                input = input,
                onInputChange = { input = it.filter(Char::isDigit).take(2) },
                onSave = {
                    val normalized = (input.toIntOrNull() ?: 5).coerceIn(1, 60)
                    input = normalized.toString()
                    onSaveDuration(normalized)
                }
            )

            DiagnosticsCard(
                diagnosticStatus = diagnosticStatus,
                onRefreshDiagnostics = onRefreshDiagnostics,
                onTestOverlay = onTestOverlay,
                testEnabled = translationEnabled
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HeroCard(
    dismissSeconds: Int,
    translationEnabled: Boolean,
    overlayGranted: Boolean,
    accessibilityEnabled: Boolean,
    rootKeepAliveHealthy: Boolean
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 14.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = InkGreen)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF0C5557), Color(0xFF133B4A), Color(0xFFB8632A))
                    )
                )
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "拷贝即译",
                    color = Color.White,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Black,
                    fontSize = 34.sp
                )
                Text(
                    text = if (translationEnabled) {
                        "复制英文单词，右上角浮出中文释义。保活只在开启状态下工作。"
                    } else {
                        "当前已暂停。复制英文不会弹窗，root watchdog 也会停止。"
                    },
                    color = Color.White.copy(alpha = 0.86f),
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(if (translationEnabled) "翻译开启" else "已暂停", translationEnabled)
                    StatusPill(if (overlayGranted) "悬浮窗已开" else "悬浮窗待开", overlayGranted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(if (accessibilityEnabled) "辅助已开" else "辅助建议开", accessibilityEnabled)
                    StatusPill(if (rootKeepAliveHealthy) "保活在线" else "保活未运行", rootKeepAliveHealthy)
                    StatusPill("${dismissSeconds}s 自动消失", true)
                }
            }
        }
    }
}

@Composable
private fun ControlCard(
    translationEnabled: Boolean,
    onEnableTranslation: () -> Unit,
    onPauseTranslation: () -> Unit
) {
    GlassCard {
        Text("总开关", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(
            text = if (translationEnabled) {
                "开启时会后台监听复制，并使用 root 保活。关闭后复制不会弹窗。"
            } else {
                "暂停后不会监听复制，也不会被 root watchdog 自动拉起。"
            },
            color = Muted,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp)
        )

        if (translationEnabled) {
            OutlinedButton(
                onClick = onPauseTranslation,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Copper.copy(alpha = 0.42f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Copper)
            ) {
                Text("暂停拷贝即译")
            }
        } else {
            PrimaryButton(
                text = "开启拷贝即译",
                onClick = onEnableTranslation,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun StatusPill(text: String, active: Boolean) {
    val color = if (active) Color(0xFFCFF5DE) else Color(0xFFFFE1B8)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PermissionCard(
    title: String,
    body: String,
    statusText: String,
    statusColor: Color,
    buttonText: String,
    onClick: () -> Unit
) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(statusColor)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(statusText, fontSize = 12.sp, color = statusColor, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onClick,
                border = BorderStroke(1.dp, InkGreen.copy(alpha = 0.28f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = InkGreen)
            ) {
                Text(buttonText)
            }
        }
        Text(
            text = body,
            color = Muted,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun DurationCard(
    input: String,
    onInputChange: (String) -> Unit,
    onSave: () -> Unit
) {
    GlassCard {
        Text("悬浮窗时长", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(
            text = "默认 5 秒，可在 1 到 60 秒之间自定义。",
            color = Muted,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text("秒数") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            PrimaryButton(text = "保存", onClick = onSave)
        }
    }
}

@Composable
private fun DiagnosticsCard(
    diagnosticStatus: String,
    onRefreshDiagnostics: () -> Unit,
    onTestOverlay: () -> Unit,
    testEnabled: Boolean
) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "运行状态",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onRefreshDiagnostics,
                border = BorderStroke(1.dp, InkGreen.copy(alpha = 0.25f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = InkGreen)
            ) {
                Text("刷新")
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFF4F0E8))
                .padding(14.dp)
        ) {
            Text(
                text = diagnosticStatus.ifBlank { "暂无状态。请先开启拷贝即译或点击测试。" },
                color = Muted,
                lineHeight = 20.sp,
                fontSize = 13.sp
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
            onClick = onTestOverlay,
            enabled = testEnabled,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, InkGreen.copy(alpha = 0.25f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = InkGreen)
        ) {
            Text("测试 hello 悬浮窗")
        }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White.copy(alpha = 0.92f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            content = content
        )
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = InkGreen, contentColor = Color.White),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(color)
    )
}

private val Paper = Color(0xFFFFF8EC)
private val Ink = Color(0xFF17252A)
private val Muted = Color(0xFF62706F)
private val InkGreen = Color(0xFF0E5C62)
private val SeaGlass = Color(0xFF7EB7A7)
private val Copper = Color(0xFFB8632A)
private val Healthy = Color(0xFF1B8A5A)
private val Warning = Color(0xFFD08A22)
