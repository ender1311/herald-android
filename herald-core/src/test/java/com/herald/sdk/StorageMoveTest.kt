package com.herald.sdk

import com.herald.sdk.internal.StateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

// audit 2026-11-17: Android SDK state, user token and queued PII are included in Auto Backup by default
class StorageMoveTest {
    private fun root(): File = Files.createTempDirectory("herald-storage").toFile()

    @Test
    fun movesTheOldFilesDirectoryOutOfBackup() {
        val root = root()
        val legacy = File(root, "files/herald").apply { mkdirs() }
        File(legacy, StateStore.FILE_NAME).writeText("""{"deviceId":"d1","userToken":"t"}""")
        File(legacy, "${StateStore.FILE_NAME}.disabled").createNewFile()
        val target = File(root, "no_backup/herald")

        assertEquals(target, StateStore.moveLegacy(legacy, target))
        assertFalse(legacy.exists())
        assertEquals("""{"deviceId":"d1","userToken":"t"}""", File(target, StateStore.FILE_NAME).readText())
        assertTrue(StateStore.isDisabled(target))
    }

    @Test
    fun keepsTheNewDirectoryAndDropsAStaleOldOne() {
        val root = root()
        val legacy = File(root, "files/herald").apply { mkdirs() }
        File(legacy, StateStore.FILE_NAME).writeText("old")
        val target = File(root, "no_backup/herald").apply { mkdirs() }
        File(target, StateStore.FILE_NAME).writeText("new")

        assertEquals(target, StateStore.moveLegacy(legacy, target))
        assertFalse(legacy.exists())
        assertEquals("new", File(target, StateStore.FILE_NAME).readText())
    }

    @Test
    fun aFreshInstallUsesTheNewDirectory() {
        val root = root()
        val target = File(root, "no_backup/herald")
        assertEquals(target, StateStore.moveLegacy(File(root, "files/herald"), target))
        assertFalse(target.exists())
    }
}
