package com.meetspace.app

import org.junit.Assert.*
import org.junit.Test

class MessagingFilesTest {
    @Test fun filenamesCannotCarryAPathAcrossPickerAndExportBoundaries() {
        assertEquals("report.pdf", MessagingFiles.safeName("../../folder/report.pdf"))
        assertEquals("report.pdf", MessagingFiles.safeName("C:\\private\\report.pdf"))
        assertEquals("Отчёт.pdf", MessagingFiles.safeName(" mixed/path\\Отчёт.pdf"))
    }
    @Test fun filenameControlsAreRemovedAndBlankNamesGetUsableFallback() {
        assertEquals("name.txt", MessagingFiles.safeName("\u0000name\r\n.txt\t"))
        assertEquals("Вложение", MessagingFiles.safeName("\r\n\u0000  "))
        assertEquals("Вложение", MessagingFiles.safeName("/"))
    }
    @Test fun providerNamesAreBoundedAndOrdinaryUnicodeRemainsReadable() {
        assertEquals(180, MessagingFiles.safeName("x".repeat(500)).length)
        assertEquals("Материалы 📄.pdf", MessagingFiles.safeName(" Материалы 📄.pdf "))
    }
}
