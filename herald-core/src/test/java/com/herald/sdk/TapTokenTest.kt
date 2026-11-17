package com.herald.sdk

import com.herald.sdk.internal.TapToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

// audit 2026-11-17: Android handleNotificationIntent trusts extras on any intent sent to the exported launcher activity
class TapTokenTest {
    private fun tempDir(): File = Files.createTempDirectory("herald-tap").toFile()

    @Test
    fun createsOneRandomTokenPerInstallAndKeepsIt() {
        val dir = tempDir()
        val token = TapToken.get(dir)
        assertEquals(32, token.length)
        assertEquals(token, TapToken.get(dir))
        assertEquals(token, File(dir, TapToken.FILE_NAME).readText())
        assertNotEquals(token, TapToken.get(tempDir()))
    }

    @Test
    fun onlyTheStoredTokenIsTrusted() {
        val dir = tempDir()
        val token = TapToken.get(dir)
        assertTrue(TapToken.matches(dir, token))
        assertFalse(TapToken.matches(dir, null))
        assertFalse(TapToken.matches(dir, ""))
        assertFalse(TapToken.matches(dir, "0".repeat(32)))
        assertFalse(TapToken.matches(dir, token.dropLast(1)))
    }
}
