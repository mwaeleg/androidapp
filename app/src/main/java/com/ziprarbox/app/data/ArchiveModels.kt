package com.ziprarbox.app.data

import android.net.Uri

enum class ArchiveFormat(val label: String) {
    ZIP("ZIP"),
    RAR4("RAR (v4)"),
    RAR5("RAR5"),
    UNKNOWN("Unknown")
}

enum class CompressionPreset(val label: String) {
    STORE("Store (0%)"),
    FAST("Fast"),
    NORMAL("Normal"),
    MAXIMUM("Maximum")
}

data class SelectedSourceFile(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long
)

data class ArchiveEntryItem(
    val path: String,
    val uncompressedSize: Long,
    val compressedSize: Long,
    val isDirectory: Boolean,
    val isEncrypted: Boolean
)

data class ArchiveInspectionResult(
    val uri: Uri,
    val displayName: String,
    val format: ArchiveFormat,
    val entries: List<ArchiveEntryItem>,
    val isEncrypted: Boolean,
    val totalUncompressedBytes: Long
)

data class OperationProgress(
    val active: Boolean = false,
    val label: String = "",
    val currentFile: String = "",
    val completedItems: Int = 0,
    val totalItems: Int = 0
) {
    val fraction: Float
        get() = if (totalItems <= 0) 0f else (completedItems.toFloat() / totalItems.toFloat()).coerceIn(0f, 1f)
}
