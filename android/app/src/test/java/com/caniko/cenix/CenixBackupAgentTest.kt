package com.caniko.cenix

import android.app.backup.BackupAgent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
}
