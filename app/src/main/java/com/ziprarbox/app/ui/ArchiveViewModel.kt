package com.ziprarbox.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ziprarbox.app.data.ArchiveEngine
import com.ziprarbox.app.data.ArchiveInspectionResult
import com.ziprarbox.app.data.CompressionPreset
import com.ziprarbox.app.data.OperationProgress
import com.ziprarbox.app.data.SelectedSourceFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MainTab(val title: String) {
    COMPRESS("Compress"),
    EXTRACT("Extract")
}

data class ArchiveUiState(
    val activeTab: MainTab = MainTab.COMPRESS,
    val selectedFilesToCompress: List<SelectedSourceFile> = emptyList(),
    val compressionPreset: CompressionPreset = CompressionPreset.NORMAL,
    val compressPassword: String = "",
    val selectedArchiveUri: Uri? = null,
    val inspectionResult: ArchiveInspectionResult? = null,
    val extractPassword: String = "",
    val progress: OperationProgress = OperationProgress(),
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class ArchiveViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = ArchiveEngine(application.applicationContext)
    private val _uiState = MutableStateFlow(ArchiveUiState())
    val uiState: StateFlow<ArchiveUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(activeTab = tab, errorMessage = null) }
    }

    fun addFilesToCompress(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        val existingUris = _uiState.value.selectedFilesToCompress.map { it.uri }.toSet()
        val additions = uris.filterNot { it in existingUris }.map { uri ->
            SelectedSourceFile(
                uri = uri,
                displayName = ArchiveEngine.queryDisplayName(resolver, uri) ?: "file",
                sizeBytes = ArchiveEngine.queryFileSize(resolver, uri)
            )
        }
        _uiState.update {
            it.copy(
                selectedFilesToCompress = it.selectedFilesToCompress + additions,
                errorMessage = null
            )
        }
    }

    fun removeFileToCompress(uri: Uri) {
        _uiState.update { state ->
            state.copy(selectedFilesToCompress = state.selectedFilesToCompress.filterNot { it.uri == uri })
        }
    }

    fun clearFilesToCompress() {
        _uiState.update { it.copy(selectedFilesToCompress = emptyList()) }
    }

    fun setCompressionPreset(preset: CompressionPreset) {
        _uiState.update { it.copy(compressionPreset = preset) }
    }

    fun setCompressPassword(password: String) {
        _uiState.update { it.copy(compressPassword = password) }
    }

    fun setExtractPassword(password: String) {
        _uiState.update { it.copy(extractPassword = password) }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun cancelActiveOperation() {
        activeJob?.cancel()
        activeJob = null
        _uiState.update {
            it.copy(
                progress = OperationProgress(),
                statusMessage = "Operation cancelled."
            )
        }
    }

    fun compressSelectedFilesTo(outputZipUri: Uri) {
        val state = _uiState.value
        if (state.selectedFilesToCompress.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Add at least one file before compressing.") }
            return
        }

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    errorMessage = null,
                    statusMessage = null,
                    progress = OperationProgress(
                        active = true,
                        label = "Compressing to ZIP...",
                        completedItems = 0,
                        totalItems = state.selectedFilesToCompress.size
                    )
                )
            }
            try {
                val count = engine.compressToZip(
                    sources = state.selectedFilesToCompress,
                    outputZipUri = outputZipUri,
                    preset = state.compressionPreset,
                    password = state.compressPassword.ifEmpty { null }
                ) { completed, total, currentName ->
                    _uiState.update {
                        it.copy(
                            progress = OperationProgress(
                                active = true,
                                label = "Compressing to ZIP...",
                                currentFile = currentName,
                                completedItems = completed,
                                totalItems = total
                            )
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        progress = OperationProgress(),
                        statusMessage = "Created ZIP archive with $count file(s)."
                    )
                }
            } catch (_: CancellationException) {
                _uiState.update { it.copy(progress = OperationProgress()) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        progress = OperationProgress(),
                        errorMessage = e.localizedMessage ?: "Compression failed."
                    )
                }
            }
        }
    }

    fun selectArchiveToExtract(uri: Uri) {
        _uiState.update {
            it.copy(
                activeTab = MainTab.EXTRACT,
                selectedArchiveUri = uri,
                inspectionResult = null,
                errorMessage = null,
                statusMessage = null
            )
        }
        inspectSelectedArchive()
    }

    fun inspectSelectedArchive() {
        val uri = _uiState.value.selectedArchiveUri ?: return
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    errorMessage = null,
                    progress = OperationProgress(active = true, label = "Inspecting archive...")
                )
            }
            try {
                val result = engine.inspectArchive(
                    uri = uri,
                    password = _uiState.value.extractPassword.ifEmpty { null }
                )
                _uiState.update {
                    it.copy(
                        inspectionResult = result,
                        progress = OperationProgress()
                    )
                }
            } catch (_: CancellationException) {
                _uiState.update { it.copy(progress = OperationProgress()) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        progress = OperationProgress(),
                        errorMessage = e.localizedMessage ?: "Unable to read archive."
                    )
                }
            }
        }
    }

    fun extractSelectedArchiveTo(destinationTreeUri: Uri) {
        val uri = _uiState.value.selectedArchiveUri
        if (uri == null) {
            _uiState.update { it.copy(errorMessage = "Select a ZIP or RAR archive first.") }
            return
        }

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    errorMessage = null,
                    statusMessage = null,
                    progress = OperationProgress(active = true, label = "Extracting archive...")
                )
            }
            try {
                val extractedCount = engine.extractArchive(
                    archiveUri = uri,
                    destinationTreeUri = destinationTreeUri,
                    password = _uiState.value.extractPassword.ifEmpty { null }
                ) { completed, total, currentName ->
                    _uiState.update {
                        it.copy(
                            progress = OperationProgress(
                                active = true,
                                label = "Extracting archive...",
                                currentFile = currentName,
                                completedItems = completed,
                                totalItems = total
                            )
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        progress = OperationProgress(),
                        statusMessage = "Extracted $extractedCount file(s) successfully."
                    )
                }
            } catch (_: CancellationException) {
                _uiState.update { it.copy(progress = OperationProgress()) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        progress = OperationProgress(),
                        errorMessage = e.localizedMessage ?: "Extraction failed. Check the password or archive."
                    )
                }
            }
        }
    }
}
