package com.socialgrab.downloader

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var sharedUrl by mutableStateOf("")

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* si lo niega, la descarga sigue funcionando, solo sin notificacion visible */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DownloaderScreen(initialUrl = sharedUrl)
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

private fun QualityOption.label(): String {
    val parts = mutableListOf("${height}p")
    fps?.takeIf { it > 30 }?.let { parts.add("${it}fps") }
    val sizeText = filesizeBytes?.let { " • ~${it / (1024 * 1024)} MB" } ?: ""
    return "${parts.joinToString(" ")} ($ext)$sizeText"
}

@Composable
fun DownloaderScreen(initialUrl: String) {
    val context = LocalContext.current

    var videoUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var searchStage by remember { mutableStateOf<SearchStage>(SearchStage.Idle) }
    var selectedQuality by remember { mutableStateOf<QualityOption?>(null) }
    val downloadStage by DownloadStatus.stage.collectAsState()

    val scope = rememberCoroutineScope()
    val extractor = remember { Extractor(context) }

    fun searchQualities() {
        if (videoUrl.isBlank()) {
            searchStage = SearchStage.Failed("Pega un link primero.")
            return
        }
        val url = videoUrl
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Little Star", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = videoUrl,
            onValueChange = { videoUrl = it },
            label = { Text("Link de TikTok / YouTube / etc.") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { videoUrl = "" }) {
                    Text("✕")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { searchQualities() },
                enabled = searchStage !is SearchStage.Resolving,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (searchStage is SearchStage.Resolving) "Buscando..." else "Descargar")
            }

            OutlinedButton(
                onClick = {
                    searchStage = SearchStage.Idle
                    selectedQuality = null
                    DownloadStatus.stage.value = DownloadStage.Idle
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Limpiar interfaz")
            }
        }

        when (val current = searchStage) {
            is SearchStage.Choosing -> {
                Text(current.title, style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    current.qualities.forEach { quality ->
                        val isSelected = selectedQuality == quality
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !downloadStage.isBusy()) {
                                    selectedQuality = quality
                                },
                            colors = if (isSelected) {
                                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            } else {
                                CardDefaults.cardColors()
                            },
                            border = if (isSelected) {
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                null
                            },
                        ) {
                            Text(quality.label(), modifier = Modifier.padding(16.dp))
                        }
                    }
                }

                Button(
                    onClick = {
                        val quality = selectedQuality ?: return@Button
                        DownloadService.start(context, current.url, quality.formatId, quality.hasAudio)
                    },
                    enabled = selectedQuality != null && !downloadStage.isBusy(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Descargar en este formato")
                }
            }

            is SearchStage.Failed -> Text("Error: ${current.message}")
            else -> {}
        }

        DownloadStatusView(downloadStage)
    }
}

@Composable
private fun DownloadStatusView(stage: DownloadStage) {
    val progress = when (stage) {
        is DownloadStage.DownloadingVideo -> stage.progress
        is DownloadStage.DownloadingAudio -> stage.progress
        is DownloadStage.Combining -> stage.progress
        else -> null
    }

    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val text = when (stage) {
        DownloadStage.Idle -> ""
        DownloadStage.PreparingDownload -> "Preparando descarga..."
        is DownloadStage.DownloadingVideo -> "Descargando video... ${stage.progress}%"
        is DownloadStage.DownloadingAudio -> "Descargando audio... ${stage.progress}%"
        is DownloadStage.Combining -> "Combinando video y audio... ${stage.progress}%"
        DownloadStage.Saving -> "Guardando..."
        is DownloadStage.Done -> "Guardado en Descargas/LittleStar/${stage.fileName}"
        is DownloadStage.Failed -> "Error: ${stage.message}"
    }

    if (text.isNotBlank()) {
        Text(text)
    }
}
