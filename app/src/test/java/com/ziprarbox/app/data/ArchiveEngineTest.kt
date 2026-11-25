package com.ziprarbox.app.data

import net.lingala.zip4j.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveEngineTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun detectFormatFromHeader_identifiesZipRar4Rar5() {
        val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0)
        val rar4Header = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0)
        val rar5Header = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
        val unknownHeader = byteArrayOf(0x00, 0x01, 0x02, 0x03)

        assertEquals(ArchiveFormat.ZIP, ArchiveEngine.detectFormatFromHeader(zipHeader, 8))
        assertEquals(ArchiveFormat.RAR4, ArchiveEngine.detectFormatFromHeader(rar4Header, 8))
        assertEquals(ArchiveFormat.RAR5, ArchiveEngine.detectFormatFromHeader(rar5Header, 8))
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveEngine.detectFormatFromHeader(unknownHeader, 4))
    }

    @Test
    fun sanitizeRelativePath_blocksTraversal() {
        assertEquals("folder/file.txt", ArchiveEngine.sanitizeRelativePath("../../folder/./file.txt"))
        assertEquals("nested/doc.pdf", ArchiveEngine.sanitizeRelativePath("\\..\\nested\\doc.pdf"))
        assertEquals("", ArchiveEngine.sanitizeRelativePath("../../"))
    }

    @Test(expected = SecurityException::class)
    fun resolveSafeFile_rejectsEscapeOutsideRoot() {
        val root = tempFolder.newFolder("extract_root")
        ArchiveEngine.resolveSafeFile(root, "../evil.txt")
    }

    @Test
    fun zip4j_encryptedRoundTripWorks() {
        val src = tempFolder.newFile("sample.txt").apply { writeText("Hello ZipRarBox!") }
        val zipPath = File(tempFolder.root, "secure.zip")
        val password = "secret-pass"

        val params = ArchiveEngine.buildZipParameters(CompressionPreset.MAXIMUM, encrypted = true)
        ZipFile(zipPath, password.toCharArray()).addFile(src, params)

        val outDir = tempFolder.newFolder("out")
        ZipFile(zipPath, password.toCharArray()).extractAll(outDir.absolutePath)

        val extracted = File(outDir, "sample.txt")
        assertTrue(extracted.exists())
        assertEquals("Hello ZipRarBox!", extracted.readText())
    }
}
