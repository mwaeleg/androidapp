package com.ziprarbox.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader as RarFileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Core archive engine supporting:
 * - ZIP compression (Store/Fast/Normal/Maximum + optional AES-256 encryption) via Zip4j
 * - ZIP inspection & extraction (including password-protected archives) via Zip4j
 * - RAR (v1-v4) inspection & extraction via Junrar
 * - Magic-byte format detection (ZIP, RAR4, RAR5) and Zip Slip path sanitization
 *
 * Note on RAR5 & RAR creation:
 * - RAR5 extraction requires native bindings (e.g., 7-Zip-JBinding-4Android).
 * - RAR compression is proprietary to RARLAB (WinRAR); open-source creation is not available.
 */
class ArchiveEngine(private val context: Context) {

    suspend fun inspectArchive(uri: Uri, password: String? = null): ArchiveInspectionResult =
        withContext(Dispatchers.IO) {
            val displayName = queryDisplayName(context.contentResolver, uri) ?: "archive"
            val tempFile = copyUriToTempFile(uri, "inspect_")
            try {
                val format = detectFormat(tempFile)
                when (format) {
                    ArchiveFormat.ZIP -> inspectZip(uri, displayName, tempFile, password)
                    ArchiveFormat.RAR4 -> inspectRar4(uri, displayName, tempFile, password)
                    ArchiveFormat.RAR5 -> throw UnsupportedOperationException(
                        "RAR5 archives require native unrar bindings (such as 7-Zip-JBinding-4Android). " +
                            "See README.md for adding RAR5 native extraction support."
                    )
                    ArchiveFormat.UNKNOWN -> throw IllegalArgumentException(
                        "Unsupported or corrupted archive format: $displayName"
                    )
                }
            } finally {
                tempFile.delete()
            }
        }

    suspend fun compressToZip(
        sources: List<SelectedSourceFile>,
        outputZipUri: Uri,
        preset: CompressionPreset,
        password: String?,
        onProgress: (completed: Int, total: Int, currentName: String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        require(sources.isNotEmpty()) { "Select at least one file to compress." }

        val workDir = File(context.cacheDir, "zip_work_${System.currentTimeMillis()}").apply { mkdirs() }
        val stagingZip = File(workDir, "output.zip")

        try {
            val zipFile = if (!password.isNullOrEmpty()) {
                ZipFile(stagingZip, password.toCharArray())
            } else {
                ZipFile(stagingZip)
            }

            val baseParams = buildZipParameters(preset, !password.isNullOrEmpty())
            val usedNames = mutableSetOf<String>()

            sources.forEachIndexed { index, item ->
                coroutineContext.ensureActive()
                val safeName = deduplicateFileName(sanitizeEntryName(item.displayName), usedNames)
                onProgress(index, sources.size, safeName)

                val stagedSource = File(workDir, safeName)
                context.contentResolver.openInputStream(item.uri)?.use { input ->
                    FileOutputStream(stagedSource).use { output ->
                        input.copyTo(output, bufferSize = DEFAULT_BUFFER_SIZE)
                    }
                } ?: throw IOException("Unable to read source file: ${item.displayName}")

                val entryParams = ZipParameters(baseParams).apply {
                    fileNameInZip = safeName
                }
                zipFile.addFile(stagedSource, entryParams)
                stagedSource.delete()
                onProgress(index + 1, sources.size, safeName)
            }

            context.contentResolver.openOutputStream(outputZipUri, "w")?.use { out ->
                FileInputStream(stagingZip).use { input ->
                    input.copyTo(out, bufferSize = DEFAULT_BUFFER_SIZE)
                }
            } ?: throw IOException("Unable to write to destination ZIP file.")

            sources.size
        } finally {
            workDir.deleteRecursively()
        }
    }

    suspend fun extractArchive(
        archiveUri: Uri,
        destinationTreeUri: Uri,
        password: String?,
        onProgress: (completed: Int, total: Int, currentName: String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val targetRoot = DocumentFile.fromTreeUri(context, destinationTreeUri)
            ?: throw IOException("Unable to access destination folder.")

        val tempArchive = copyUriToTempFile(archiveUri, "extract_")
        val stagingDir = File(context.cacheDir, "extract_stage_${System.currentTimeMillis()}").apply { mkdirs() }

        try {
            val format = detectFormat(tempArchive)
            val extractedCount = when (format) {
                ArchiveFormat.ZIP -> extractZipToDir(tempArchive, stagingDir, password, onProgress)
                ArchiveFormat.RAR4 -> extractRar4ToDir(tempArchive, stagingDir, password, onProgress)
                ArchiveFormat.RAR5 -> throw UnsupportedOperationException(
                    "RAR5 extraction requires native unrar bindings (see README.md)."
                )
                ArchiveFormat.UNKNOWN -> throw IllegalArgumentException(
                    "File is not a supported ZIP or RAR archive."
                )
            }

            copyDirectoryToDocumentTree(stagingDir, targetRoot)
            extractedCount
        } finally {
            tempArchive.delete()
            stagingDir.deleteRecursively()
        }
    }

    private fun inspectZip(
        uri: Uri,
        displayName: String,
        file: File,
        password: String?
    ): ArchiveInspectionResult {
        val zipFile = if (!password.isNullOrEmpty()) {
            ZipFile(file, password.toCharArray())
        } else {
            ZipFile(file)
        }
        val headers = zipFile.fileHeaders
        val entries = headers.map { header ->
            ArchiveEntryItem(
                path = header.fileName,
                uncompressedSize = header.uncompressedSize.coerceAtLeast(0L),
                compressedSize = header.compressedSize.coerceAtLeast(0L),
                isDirectory = header.isDirectory,
                isEncrypted = header.isEncrypted
            )
        }
        return ArchiveInspectionResult(
            uri = uri,
            displayName = displayName,
            format = ArchiveFormat.ZIP,
            entries = entries,
            isEncrypted = zipFile.isEncrypted,
            totalUncompressedBytes = entries.sumOf { it.uncompressedSize }
        )
    }

    private fun inspectRar4(
        uri: Uri,
        displayName: String,
        file: File,
        password: String?
    ): ArchiveInspectionResult {
        val archive = if (password.isNullOrEmpty()) Archive(file) else Archive(file, password)
        archive.use { rar ->
            val entries = rar.fileHeaders.map { header: RarFileHeader ->
                ArchiveEntryItem(
                    path = header.fileName.replace('\\', '/'),
                    uncompressedSize = header.fullUnpackSize.coerceAtLeast(0L),
                    compressedSize = header.fullPackSize.coerceAtLeast(0L),
                    isDirectory = header.isDirectory,
                    isEncrypted = header.isEncrypted
                )
            }
            return ArchiveInspectionResult(
                uri = uri,
                displayName = displayName,
                format = ArchiveFormat.RAR4,
                entries = entries,
                isEncrypted = entries.any { it.isEncrypted },
                totalUncompressedBytes = entries.sumOf { it.uncompressedSize }
            )
        }
    }

    private suspend fun extractZipToDir(
        archiveFile: File,
        destDir: File,
        password: String?,
        onProgress: (Int, Int, String) -> Unit
    ): Int {
        val zipFile = if (!password.isNullOrEmpty()) {
            ZipFile(archiveFile, password.toCharArray())
        } else {
            ZipFile(archiveFile)
        }

        val headers = zipFile.fileHeaders
        val total = headers.size.coerceAtLeast(1)
        var extractedFiles = 0

        headers.forEachIndexed { index, header ->
            coroutineContext.ensureActive()
            val safePath = sanitizeRelativePath(header.fileName)
            if (safePath.isEmpty()) return@forEachIndexed

            val target = resolveSafeFile(destDir, safePath)
            onProgress(index, total, safePath)

            if (header.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                zipFile.extractFile(header, destDir.absolutePath, safePath)
                extractedFiles++
            }
            onProgress(index + 1, total, safePath)
        }
        return extractedFiles
    }

    private suspend fun extractRar4ToDir(
        archiveFile: File,
        destDir: File,
        password: String?,
        onProgress: (Int, Int, String) -> Unit
    ): Int {
        val archive = if (password.isNullOrEmpty()) Archive(archiveFile) else Archive(archiveFile, password)
        archive.use { rar ->
            val headers = rar.fileHeaders
            val total = headers.size.coerceAtLeast(1)
            var extractedFiles = 0

            headers.forEachIndexed { index, header ->
                coroutineContext.ensureActive()
                val normalized = header.fileName.replace('\\', '/')
                val safePath = sanitizeRelativePath(normalized)
                if (safePath.isEmpty()) return@forEachIndexed

                val target = resolveSafeFile(destDir, safePath)
                onProgress(index, total, safePath)

                if (header.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        rar.extractFile(header, out)
                    }
                    extractedFiles++
                }
                onProgress(index + 1, total, safePath)
            }
            return extractedFiles
        }
    }

    private fun copyDirectoryToDocumentTree(sourceDir: File, targetDir: DocumentFile) {
        val children = sourceDir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                val subDoc = targetDir.findFile(child.name)?.takeIf { it.isDirectory }
                    ?: targetDir.createDirectory(child.name)
                    ?: throw IOException("Unable to create folder: ${child.name}")
                copyDirectoryToDocumentTree(child, subDoc)
            } else {
                val mime = guessMimeType(child.name)
                targetDir.findFile(child.name)?.delete()
                val docFile = targetDir.createFile(mime, child.name)
                    ?: throw IOException("Unable to create file: ${child.name}")
                context.contentResolver.openOutputStream(docFile.uri, "w")?.use { out ->
                    FileInputStream(child).use { input ->
                        input.copyTo(out, bufferSize = DEFAULT_BUFFER_SIZE)
                    }
                } ?: throw IOException("Unable to write extracted file: ${child.name}")
            }
        }
    }

    private fun copyUriToTempFile(uri: Uri, prefix: String): File {
        val temp = File.createTempFile(prefix, ".tmp", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output, bufferSize = DEFAULT_BUFFER_SIZE)
            }
        } ?: throw IOException("Unable to open selected archive.")
        return temp
    }

    companion object {
        private val ZIP_LOCAL_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val ZIP_EMPTY_HEADER = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
        private val RAR4_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        private val RAR5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

        fun detectFormat(file: File): ArchiveFormat {
            if (!file.exists() || file.length() < 4) return ArchiveFormat.UNKNOWN
            val header = ByteArray(8)
            val bytesRead = FileInputStream(file).use { it.read(header) }
            return detectFormatFromHeader(header, bytesRead)
        }

        fun detectFormatFromHeader(header: ByteArray, length: Int): ArchiveFormat {
            if (length >= 8 && header.sliceArray(0 until 8).contentEquals(RAR5_SIGNATURE)) {
                return ArchiveFormat.RAR5
            }
            if (length >= 7 && header.sliceArray(0 until 7).contentEquals(RAR4_SIGNATURE)) {
                return ArchiveFormat.RAR4
            }
            if (length >= 4) {
                val first4 = header.sliceArray(0 until 4)
                if (first4.contentEquals(ZIP_LOCAL_HEADER) || first4.contentEquals(ZIP_EMPTY_HEADER)) {
                    return ArchiveFormat.ZIP
                }
            }
            return ArchiveFormat.UNKNOWN
        }

        fun buildZipParameters(preset: CompressionPreset, encrypted: Boolean): ZipParameters {
            return ZipParameters().apply {
                when (preset) {
                    CompressionPreset.STORE -> {
                        compressionMethod = CompressionMethod.STORE
                        compressionLevel = CompressionLevel.NO_COMPRESSION
                    }
                    CompressionPreset.FAST -> {
                        compressionMethod = CompressionMethod.DEFLATE
                        compressionLevel = CompressionLevel.FAST
                    }
                    CompressionPreset.NORMAL -> {
                        compressionMethod = CompressionMethod.DEFLATE
                        compressionLevel = CompressionLevel.NORMAL
                    }
                    CompressionPreset.MAXIMUM -> {
                        compressionMethod = CompressionMethod.DEFLATE
                        compressionLevel = CompressionLevel.MAXIMUM
                    }
                }
                if (encrypted) {
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                }
            }
        }

        /**
         * Strips traversal segments (`..`), drive prefixes, and leading slashes to prevent Zip Slip.
         */
        fun sanitizeRelativePath(rawPath: String): String {
            val normalized = rawPath.replace('\\', '/').trim()
            val segments = normalized.split('/')
                .filter { it.isNotBlank() && it != "." && it != ".." && !it.contains(':') }
            return segments.joinToString("/")
        }

        fun sanitizeEntryName(name: String): String {
            val cleaned = name.replace('\\', '/').substringAfterLast('/').trim()
            return cleaned.ifEmpty { "file" }
        }

        fun resolveSafeFile(destDir: File, relativePath: String): File {
            val destCanonical = destDir.canonicalFile
            val candidate = File(destCanonical, relativePath).canonicalFile
            val destPrefix = destCanonical.path + File.separator
            if (candidate.path != destCanonical.path && !candidate.path.startsWith(destPrefix)) {
                throw SecurityException("Blocked archive path traversal attempt: $relativePath")
            }
            return candidate
        }

        fun deduplicateFileName(name: String, usedNames: MutableSet<String>): String {
            if (usedNames.add(name)) return name
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var counter = 1
            while (true) {
                val candidate = "${base}_$counter$ext"
                if (usedNames.add(candidate)) return candidate
                counter++
            }
        }

        fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
            return uri.lastPathSegment
        }

        fun queryFileSize(resolver: ContentResolver, uri: Uri): Long {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
                }
            }
            return 0L
        }

        private fun guessMimeType(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return "application/octet-stream"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
