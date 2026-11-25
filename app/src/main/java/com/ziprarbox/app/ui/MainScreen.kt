package com.ziprarbox.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziprarbox.app.data.ArchiveEntryItem
import com.ziprarbox.app.data.CompressionPreset
import com.ziprarbox.app.data.OperationProgress
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ArchiveViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val pickFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        viewModel.addFilesToCompress(uris)
    }

    val createZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) viewModel.compressSelectedFilesTo(uri)
    }

    val pickArchiveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.selectArchiveToExtract(uri)
    }

    val pickDestinationFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) viewModel.extractSelectedArchiveTo(treeUri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.FolderZip,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("ZipRarBox", fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryTabRow(selectedTabIndex = state.activeTab.ordinal) {
                MainTab.entries.forEach { tab ->
                    Tab(
                        selected = state.activeTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.title) },
                        icon = {
                            Icon(
                                imageVector = if (tab == MainTab.COMPRESS) Icons.Rounded.Archive else Icons.Rounded.Unarchive,
                                contentDescription = null
                            )
                        }
                    )
                }
            }

            if (state.progress.active) {
                ProgressCard(
                    progress = state.progress,
                    onCancel = viewModel::cancelActiveOperation
                )
            }

            state.statusMessage?.let { message ->
                FeedbackBanner(
                    message = message,
                    isError = false,
                    onDismiss = viewModel::dismissBanner
                )
            }

            state.errorMessage?.let { error ->
                FeedbackBanner(
                    message = error,
                    isError = true,
                    onDismiss = viewModel::dismissBanner
                )
            }

            when (state.activeTab) {
                MainTab.COMPRESS -> CompressPane(
                    state = state,
                    onPickFiles = { pickFilesLauncher.launch(arrayOf("*/*")) },
                    onRemoveFile = viewModel::removeFileToCompress,
                    onClearAll = viewModel::clearFilesToCompress,
                    onPresetChange = viewModel::setCompressionPreset,
                    onPasswordChange = viewModel::setCompressPassword,
                    onCreateZip = { createZipLauncher.launch("archive.zip") }
                )

                MainTab.EXTRACT -> ExtractPane(
                    state = state,
                    onPickArchive = {
                        pickArchiveLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/vnd.rar",
                                "application/x-rar-compressed",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onPasswordChange = viewModel::setExtractPassword,
                    onReloadArchive = viewModel::inspectSelectedArchive,
                    onExtract = { pickDestinationFolderLauncher.launch(null) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompressPane(
    state: ArchiveUiState,
    onPickFiles: () -> Unit,
    onRemoveFile: (android.net.Uri) -> Unit,
    onClearAll: () -> Unit,
    onPresetChange: (CompressionPreset) -> Unit,
    onPasswordChange: (String) -> Unit,
    onCreateZip: () -> Unit
) {
    var showPassword by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onPickFiles,
                    enabled = !state.progress.active,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Files")
                }

                if (state.selectedFilesToCompress.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onClearAll,
                        enabled = !state.progress.active
                    ) {
                        Text("Clear")
                    }
                }
            }
        }

        item {
            Text(
                text = "Compression Level",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CompressionPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = state.compressionPreset == preset,
                        onClick = { onPresetChange(preset) },
                        label = { Text(preset.label) }
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = state.compressPassword,
                onValueChange = onPasswordChange,
                label = { Text("Optional Password (AES-256)") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = "Toggle password visibility"
                        )
                    }
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Button(
                onClick = onCreateZip,
                enabled = state.selectedFilesToCompress.isNotEmpty() && !state.progress.active,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Archive, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Compress to ZIP (${state.selectedFilesToCompress.size} file${if (state.selectedFilesToCompress.size == 1) "" else "s"})")
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Archives are created as standard ZIP (with optional AES-256 encryption). Creating .rar archives requires RARLAB's proprietary encoder.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (state.selectedFilesToCompress.isEmpty()) {
            item {
                Text(
                    text = "No files selected yet. Tap \"Add Files\" to pick documents, photos, or other files to compress.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            items(state.selectedFilesToCompress, key = { it.uri.toString() }) { item ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = formatBytes(item.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onRemoveFile(item.uri) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove file")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtractPane(
    state: ArchiveUiState,
    onPickArchive: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onReloadArchive: () -> Unit,
    onExtract: () -> Unit
) {
    var showPassword by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            FilledTonalButton(
                onClick = onPickArchive,
                enabled = !state.progress.active,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select ZIP or RAR Archive")
            }
        }

        item {
            OutlinedTextField(
                value = state.extractPassword,
                onValueChange = onPasswordChange,
                label = { Text("Archive Password (if encrypted)") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = "Toggle password visibility"
                        )
                    }
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        val info = state.inspectionResult
        if (info != null) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = info.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${info.format.label} • ${info.entries.size} entries • ${formatBytes(info.totalUncompressedBytes)} uncompressed" +
                                if (info.isEncrypted) " • Encrypted" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onExtract,
                                enabled = !state.progress.active,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Unarchive, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Extract to Folder")
                            }
                            OutlinedButton(
                                onClick = onReloadArchive,
                                enabled = !state.progress.active
                            ) {
                                Text("Refresh")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Archive Contents",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }

            items(info.entries, key = { it.path }) { entry ->
                ArchiveEntryRow(entry)
            }
        } else if (state.selectedArchiveUri == null) {
            item {
                Text(
                    text = "Choose any .zip or .rar archive to preview its files and extract them safely to a folder on your device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ArchiveEntryRow(entry: ArchiveEntryItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Rounded.FolderOpen else Icons.Rounded.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.path,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!entry.isDirectory) {
                    Text(
                        text = "${formatBytes(entry.uncompressedSize)} (compressed ${formatBytes(entry.compressedSize)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (entry.isEncrypted) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = "Encrypted entry",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun ProgressCard(
    progress: OperationProgress,
    onCancel: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(progress.label, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            if (progress.totalItems > 0) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (progress.currentFile.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = progress.currentFile,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FeedbackBanner(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isError) Icons.Rounded.Info else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                color = contentColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = contentColor)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}
