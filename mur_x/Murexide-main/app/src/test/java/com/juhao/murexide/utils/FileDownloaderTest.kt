package com.juhao.murexide.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class FileDownloaderTest {
    @Test
    fun sanitizeDownloadFileName_keepsOnlyTheLeafAndReplacesUnsafeCharacters() {
        assertEquals(
            "report_.pdf",
            sanitizeDownloadFileName("../private\\report?.pdf")
        )
    }

    @Test
    fun sanitizeDownloadFileName_usesFallbackForAnEmptyName() {
        assertEquals("download", sanitizeDownloadFileName(" ... "))
    }

    @Test
    fun downloadDisplayName_placesCollisionSuffixBeforeExtension() {
        assertEquals("report.pdf", downloadDisplayName("report.pdf", collisionIndex = 0))
        assertEquals("report(1).pdf", downloadDisplayName("report.pdf", collisionIndex = 1))
        assertEquals("archive(2)", downloadDisplayName("archive", collisionIndex = 2))
    }
}
