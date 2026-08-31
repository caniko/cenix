package com.caniko.cenix

import android.app.backup.BackupAgent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class CenixBackupAgentTest {
    @Test
    fun restoreAcceptsOnlyTheBoundedCanonicalArtifact() {
        val expected = File("build/test-backup/transport/cenix-backup.json")

        assertTrue(CenixBackupAgent.acceptsRestore(expected, expected, BackupAgent.TYPE_FILE, 1))
        assertTrue(
            CenixBackupAgent.acceptsRestore(
                expected,
                expected,
                BackupAgent.TYPE_FILE,
                BackupJsonCodec.MAX_BYTES.toLong(),
            ),
        )
        assertFalse(CenixBackupAgent.acceptsRestore(expected, expected, BackupAgent.TYPE_DIRECTORY, 1))
        assertFalse(CenixBackupAgent.acceptsRestore(expected, expected, BackupAgent.TYPE_FILE, 0))
        assertFalse(
            CenixBackupAgent.acceptsRestore(
                expected,
                expected,
                BackupAgent.TYPE_FILE,
                BackupJsonCodec.MAX_BYTES.toLong() + 1,
            ),
        )
        assertFalse(
            CenixBackupAgent.acceptsRestore(
                expected,
                File("build/test-backup/transport/other.json"),
                BackupAgent.TYPE_FILE,
                1,
            ),
        )
    }

    @Test
    fun restoreConsumesExactlyTheBoundedDeclaredSize() {
        val accepted = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2), CenixBackupAgent.readRestoreEntry(accepted, 2, true))
        assertTrue(accepted.read() == 3)

        val rejected = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        assertNull(CenixBackupAgent.readRestoreEntry(rejected, 2, false))
        assertTrue(rejected.read() == 3)

        assertThrows(IOException::class.java) {
            CenixBackupAgent.readRestoreEntry(ByteArrayInputStream(byteArrayOf(1)), 2, true)
        }
        assertThrows(IOException::class.java) {
            CenixBackupAgent.readRestoreEntry(ByteArrayInputStream(byteArrayOf(1)), 2, false)
        }
        assertThrows(IOException::class.java) {
            CenixBackupAgent.readRestoreEntry(ByteArrayInputStream(byteArrayOf()), BackupJsonCodec.MAX_BYTES + 1L, false)
        }
    }

    @Test
    fun quotaIsFailClosedAtBothLimits() {
        assertFalse(CenixBackupAgent.exceedsQuota(1, -1))
        assertFalse(CenixBackupAgent.exceedsQuota(10, 10))
        assertTrue(CenixBackupAgent.exceedsQuota(11, 10))
        assertTrue(CenixBackupAgent.exceedsQuota(BackupJsonCodec.MAX_BYTES + 1L, -1))
    }

}
