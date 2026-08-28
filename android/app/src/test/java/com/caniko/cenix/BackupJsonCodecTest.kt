package com.caniko.cenix

import com.caniko.cenix.uniffi.BackupDocument
import com.caniko.cenix.uniffi.BackupProfileRef
import com.caniko.cenix.uniffi.BackupSettings
import com.caniko.cenix.uniffi.BackupWidgetMetadata
import com.caniko.cenix.uniffi.CellRect
import com.caniko.cenix.uniffi.ComponentId
import com.caniko.cenix.uniffi.ContainerRef
import com.caniko.cenix.uniffi.Folder
import com.caniko.cenix.uniffi.FolderMember
import com.caniko.cenix.uniffi.GridSpec
import com.caniko.cenix.uniffi.ItemPayload
import com.caniko.cenix.uniffi.ProfileKind
import com.caniko.cenix.uniffi.ShortcutId
import com.caniko.cenix.uniffi.WidgetProviderId
import com.caniko.cenix.uniffi.WorkspaceItem
import com.caniko.cenix.uniffi.WorkspacePage
import com.caniko.cenix.uniffi.WorkspaceSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupJsonCodecTest {
    @Test
    fun repeatWriteIsByteIdentical() {
        val first = ByteArrayOutputStream()
        val second = ByteArrayOutputStream()
        BackupJsonCodec.write(sample(), first)
        BackupJsonCodec.write(sample(), second)
        assertArrayEquals(first.toByteArray(), second.toByteArray())
    }

    @Test
    fun parserRoundtrip() {
        val out = ByteArrayOutputStream()
        BackupJsonCodec.write(sample(), out)
        val parsed = BackupJsonCodec.readForTest(ByteArrayInputStream(out.toByteArray()))
        assertEquals(sample(), parsed)
        val again = ByteArrayOutputStream()
        BackupJsonCodec.write(parsed, again)
        assertArrayEquals(out.toByteArray(), again.toByteArray())
    }

    @Test
    fun validV1GoldenMatchesWriteAndRoundtrip() {
        val golden = fixture("valid-v1.json")
        val parsed = BackupJsonCodec.readForTest(ByteArrayInputStream(golden))
        val out = ByteArrayOutputStream()
        BackupJsonCodec.write(parsed, out)
        assertArrayEquals(golden, out.toByteArray())
        assertEquals(sample(), parsed)
    }

    @Test
    fun unknownCurrentFieldIsSkipped() {
        val parsed = BackupJsonCodec.readForTest(ByteArrayInputStream(fixture("unknown-current-field.json")))
        assertEquals(sample(), parsed)
    }

    @Test
    fun truncatedRejected() {
        assertThrows(BackupJsonException::class.java) {
            BackupJsonCodec.readForTest(ByteArrayInputStream(fixture("truncated.json")))
        }
    }

    @Test
    fun checksumCorruptRejected() {
        assertThrows(BackupJsonException::class.java) {
            BackupJsonCodec.readForTest(ByteArrayInputStream(fixture("checksum-corrupt.json")))
        }
    }

    @Test
    fun overlapRejected() {
        assertThrows(BackupJsonException::class.java) {
            BackupJsonCodec.readForTest(ByteArrayInputStream(fixture("overlap.json")))
        }
    }

    @Test
    fun duplicateIdRejected() {
        assertThrows(BackupJsonException::class.java) {
            BackupJsonCodec.readForTest(ByteArrayInputStream(fixture("duplicate-id.json")))
        }
    }

    @Test
    fun mixedProfileFolderRejected() {
        assertThrows(BackupJsonException::class.java) {
            BackupJsonCodec.readForTest(ByteArrayInputStream(fixture("mixed-profile-folder.json")))
        }
    }

    @Test
    fun privateProfileRejected() {
        assertThrows(BackupJsonException::class.java) {
            BackupJsonCodec.readForTest(ByteArrayInputStream(fixture("private-profile.json")))
        }
    }

    @Test
    fun futureVersionRejected() {
        assertThrows(BackupJsonException::class.java) {
            BackupJsonCodec.readForTest(ByteArrayInputStream(fixture("future-version.json")))
        }
    }

    @Test
    fun oversizedRejected() {
        val oversized = ByteArray(BackupJsonCodec.MAX_BYTES + 1) { '{'.code.toByte() }
        assertThrows(BackupJsonException::class.java) {
            BackupJsonCodec.readForTest(ByteArrayInputStream(oversized))
        }
    }

    @Test
    fun malformedUtf8Rejected() {
        assertThrows(BackupJsonException::class.java) {
            BackupJsonCodec.readForTest(ByteArrayInputStream(byteArrayOf(0x7b, 0xff.toByte(), 0x7d)))
        }
    }

    @Test
    fun integerOverflowRejected() {
        val payload = """{"settings":{"gridName":"4_by_5","notificationDots":true,"themedIcons":false,"autoAddApps":true},"profiles":[{"profileId":0,"kind":"personal"}],"workspace":{"grid":{"cols":4,"rows":5,"hotseatCols":4},"pages":[{"pageId":1,"rank":0}],"items":[],"folders":[]},"widgets":[],"nextItemId":9223372036854775807,"nextPageId":2}"""
        assertThrows(BackupJsonException::class.java) {
            BackupJsonCodec.readForTest(ByteArrayInputStream(envelope(payload)))
        }
    }

    @Test
    fun duplicateKeysAreRejected() {
        val duplicate = String(fixture("valid-v1.json"))
            .replaceFirst("\"version\":1", "\"version\":1,\"version\":1")
        assertThrows(BackupJsonException::class.java) {
            BackupJsonCodec.readForTest(ByteArrayInputStream(duplicate.toByteArray()))
        }
    }

    @Test
    fun nestedPayloadCannotReplaceRootPayload() {
        val valid = String(fixture("valid-v1.json"))
        val nested = valid.replaceFirst(
            "\"commit\":\"abc123\"",
            "\"commit\":\"abc123\",\"payload\":{}",
        )
        assertEquals(sample(), BackupJsonCodec.readForTest(ByteArrayInputStream(nested.toByteArray())))
    }

    @Test
    fun invalidProfilesAndWidgetsAreRejected() {
        val badProfile = sample()
        val application = badProfile.workspace.items
            .map { it.payload }
            .filterIsInstance<ItemPayload.Application>()
            .first()
        application.component.profileId = 2u
        assertRoundtripRejected(badProfile)

        val hotseatWidget = sample()
        hotseatWidget.workspace.items.first { it.payload is ItemPayload.Widget }.container = ContainerRef.Hotseat
        assertRoundtripRejected(hotseatWidget)

        val badSpan = sample()
        badSpan.widgets.single().resizeX = 1
        assertRoundtripRejected(badSpan)
    }

    private fun assertRoundtripRejected(document: BackupDocument) {
        val out = ByteArrayOutputStream()
        BackupJsonCodec.write(document, out)
        assertThrows(BackupJsonException::class.java) {
            BackupJsonCodec.readForTest(ByteArrayInputStream(out.toByteArray()))
        }
    }

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/backup/$name")!!.readBytes()

    private fun envelope(payload: String): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
        val sha = digest.joinToString("") { b -> "%02x".format(b) }
        return """{"format":"${BackupJsonCodec.FORMAT}","version":1,"source":{"version":"1.0.0","commit":"abc123"},"payload":$payload,"payloadSha256":"$sha"}""".toByteArray()
    }

    private fun sample() = BackupDocument(
        1u,
        "1.0.0",
        "abc123",
        BackupSettings("4_by_5", true, false, true),
        listOf(
            BackupProfileRef(0u, ProfileKind.PERSONAL),
            BackupProfileRef(1u, ProfileKind.WORK),
        ),
        WorkspaceSnapshot(
            0u,
            GridSpec(4, 5, 4),
            listOf(WorkspacePage(1u, 0)),
            listOf(
                WorkspaceItem(
                    3u,
                    ItemPayload.Shortcut(ShortcutId("com.example.app", "sc1", 0u)),
                    ContainerRef.Hotseat,
                    CellRect(0, 0, 1, 1),
                ),
                WorkspaceItem(
                    1u,
                    ItemPayload.Application(ComponentId("com.example.app", "Main", 0u)),
                    ContainerRef.Workspace(1u),
                    CellRect(0, 0, 1, 1),
                ),
                WorkspaceItem(
                    2u,
                    ItemPayload.Folder,
                    ContainerRef.Workspace(1u),
                    CellRect(1, 0, 1, 1),
                ),
                WorkspaceItem(
                    5u,
                    ItemPayload.Application(ComponentId("com.example.work", "Main", 1u)),
                    ContainerRef.Workspace(1u),
                    CellRect(2, 0, 1, 1),
                ),
                WorkspaceItem(
                    4u,
                    ItemPayload.Widget(WidgetProviderId("com.example.widget", "Clock", 0u)),
                    ContainerRef.Workspace(1u),
                    CellRect(0, 1, 2, 2),
                ),
            ),
            listOf(
                Folder(
                    2u,
                    "Tools",
                    listOf(
                        FolderMember(6u, ItemPayload.Application(ComponentId("com.example.app", "Files", 0u)), 0u),
                        FolderMember(7u, ItemPayload.Shortcut(ShortcutId("com.example.app", "sc2", 0u)), 1u),
                    ),
                ),
            ),
        ),
        listOf(BackupWidgetMetadata(4u, 2, 2, 2, 2)),
        8u,
        2u,
    )
}
