package com.socialgrab.downloader

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var sharedUrl by mutableStateOf("")
    private lateinit var themeSettings: ThemeSettings

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* si lo niega, la descarga sigue funcionando, solo sin notificacion visible */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeSettings = ThemeSettings(this)
        handleIncomingIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val useDark = themeSettings.isDark(isSystemInDarkTheme())
            val palette = if (useDark) DarkPalette else LightPalette

            MaterialTheme(colorScheme = palette.toScheme(useDark)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DownloaderScreen(initialUrl = sharedUrl, themeSettings = themeSettings)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedUrl = it }
        }
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1024L * 1024) "%.1f MB".format(bytes / (1024.0 * 1024)) else "${bytes / 1024} KB"

private fun QualityOption.details(): String =
    listOfNotNull(
        ext.uppercase(),
        fps?.takeIf { it > 30 }?.let { "$it fps" },
        filesizeBytes?.takeIf { it > 0 }?.let { "~${formatSize(it)}" },
    ).joinToString(" · ")

@Composable
fun DownloaderScreen(initialUrl: String, themeSettings: ThemeSettings) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val isDark = themeSettings.isDark(isSystemInDarkTheme())

    var videoUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var searchStage by remember { mutableStateOf<SearchStage>(SearchStage.Idle) }
    var selectedQuality by remember { mutableStateOf<QualityOption?>(null) }
    val downloadStage by DownloadStatus.stage.collectAsState()
    val busy = downloadStage.isBusy()

    val scope = rememberCoroutineScope()
    val extractor = remember { Extractor(context) }

    fun searchQualities() {
        if (videoUrl.isBlank()) {
            searchStage = SearchStage.Failed("Pega un link primero.")
            return
        }
        val url = videoUrl.trim()
        selectedQuality = null
        searchStage = SearchStage.Resolving
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { extractor.listQualities(url) }
                searchStage = SearchStage.Choosing(url = url, title = info.title, qualities = info.qualities)
            } catch (e: Exception) {
                searchStage = SearchStage.Failed(e.message ?: "Error desconocido")
            }
        }
    }

    fun clearAll() {
        searchStage = SearchStage.Idle
        selectedQuality = null
        DownloadStatus.stage.value = DownloadStage.Idle
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "Little Star",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Descarga videos a tu galería",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ThemeToggle(isDark = isDark, onSelect = { themeSettings.selectMode(it) })
        }

        OutlinedTextField(
            value = videoUrl,
            onValueChange = { videoUrl = it },
            label = { Text("Link del video") },
            placeholder = { Text("https://") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                if (videoUrl.isEmpty()) {
                    TextButton(onClick = {
                        clipboard.getText()?.text?.let { videoUrl = it.trim() }
                    }) {
                        Text("Pegar")
                    }
                } else {
                    IconButton(onClick = { videoUrl = "" }) {
                        Text("✕", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { searchQualities() },
                enabled = searchStage !is SearchStage.Resolving && !busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
            ) {
                Text(
                    if (searchStage is SearchStage.Resolving) "Buscando..." else "Buscar calidades",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            OutlinedButton(
                onClick = { clearAll() },
                enabled = !busy && (searchStage !is SearchStage.Idle || downloadStage !is DownloadStage.Idle),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
            ) {
                Text("Limpiar", style = MaterialTheme.typography.labelLarge)
            }
        }

        if (searchStage is SearchStage.Idle && downloadStage is DownloadStage.Idle) {
            Text(
                "Compatible con YouTube, Instagram, Facebook y X.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (val current = searchStage) {
            is SearchStage.Choosing -> ResultsSection(
                title = current.title,
                qualities = current.qualities,
                selected = selectedQuality,
                busy = busy,
                onSelect = { selectedQuality = it },
                onDownload = { quality ->
                    DownloadService.start(context, current.url, quality.formatId, quality.hasAudio)
                },
            )

            is SearchStage.Failed -> Text(
                current.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            else -> {}
        }

        StatusCard(downloadStage)
    }
}

@Composable
private fun ThemeToggle(isDark: Boolean, onSelect: (ThemeMode) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
    ) {
        ToggleOption(selected = !isDark, label = "☀️") { onSelect(ThemeMode.CLARO) }
        ToggleOption(selected = isDark, label = "🌙") { onSelect(ThemeMode.OSCURO) }
    }
}

@Composable
private fun ToggleOption(selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, modifier = Modifier.alpha(if (selected) 1f else 0.5f))
    }
}

@Composable
private fun ResultsSection(
    title: String,
    qualities: List<QualityOption>,
    selected: QualityOption?,
    busy: Boolean,
    onSelect: (QualityOption) -> Unit,
    onDownload: (QualityOption) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Elige una calidad",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        qualities.forEach { quality ->
            QualityCard(
                quality = quality,
                selected = quality == selected,
                enabled = !busy,
                onClick = { onSelect(quality) },
            )
        }

        Button(
            onClick = { selected?.let(onDownload) },
            enabled = selected != null && !busy,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
        ) {
            Text(
                if (selected != null) "Descargar ${selected.height}p" else "Elige una calidad",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun QualityCard(quality: QualityOption, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) scheme.primaryContainer else scheme.surface,
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) scheme.primary else scheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${quality.height}p",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                val details = quality.details()
                if (details.isNotEmpty()) {
                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                Text("✓", style = MaterialTheme.typography.titleMedium, color = scheme.primary)
            }
        }
    }
}

@Composable
private fun StatusCard(stage: DownloadStage) {
    if (stage is DownloadStage.Idle) return

    val progress = when (stage) {
        is DownloadStage.DownloadingVideo -> stage.progress
        is DownloadStage.DownloadingAudio -> stage.progress
        is DownloadStage.Combining -> stage.progress
        else -> null
    }

    val text = when (stage) {
        DownloadStage.Idle -> ""
        DownloadStage.PreparingDownload -> "Preparando descarga..."
        is DownloadStage.DownloadingVideo -> "Descargando video... ${stage.progress}%"
        is DownloadStage.DownloadingAudio -> "Descargando audio... ${stage.progress}%"
        is DownloadStage.Combining -> "Combinando video y audio... ${stage.progress}%"
        DownloadStage.Saving -> "Guardando..."
        is DownloadStage.Done -> "Listo: guardado en Descargas/LittleStar/${stage.fileName}"
        is DownloadStage.Failed -> "Error: ${stage.message}"
    }
    val isError = stage is DownloadStage.Failed

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
