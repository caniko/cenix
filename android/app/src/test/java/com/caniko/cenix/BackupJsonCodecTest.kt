package com.caniko.cenix

import com.caniko.cenix.uniffi.CodecException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Artifact vectors live in Rust now (cenix-core codec tests, pinned to the same
// golden fixtures). These tests cover only the thin Kotlin mapping, which must
// work without the native library loaded.
class BackupJsonCodecTest {
    @Test
    fun errorMappingPreservesReasons() {
        val cases = listOf(
            CodecException.Format() to "format",
            CodecException.Version() to "version",
            CodecException.Source() to "source",
            CodecException.Payload() to "payload",
            CodecException.Checksum() to "checksum",
            CodecException.Trailing() to "trailing",
            CodecException.Malformed() to "malformed",
            CodecException.Drawer() to "drawer",
            CodecException.Journal() to "journal",
            CodecException.Settings() to "settings",
            CodecException.Profiles() to "profiles",
            CodecException.Workspace() to "workspace",
            CodecException.Widgets() to "widgets",
            CodecException.Allocator() to "allocator",
            CodecException.Grid() to "grid",
            CodecException.Pages() to "pages",
            CodecException.Items() to "items",
            CodecException.Folders() to "folders",
            CodecException.Item() to "item",
            CodecException.Folder() to "folder",
            CodecException.Member() to "member",
            CodecException.Widget() to "widget",
            CodecException.Kind() to "kind",
            CodecException.Container() to "container",
            CodecException.Cell() to "cell",
            CodecException.Page() to "page",
            CodecException.String() to "string",
            CodecException.Integer() to "integer",
            CodecException.Count() to "count",
            CodecException.DuplicateKey() to "duplicate-key",
            CodecException.Forbidden() to "forbidden",
            CodecException.Token() to "token",
            CodecException.Truncated() to "truncated",
            CodecException.Depth() to "depth",
            CodecException.Overlap() to "overlap",
            CodecException.Span() to "span",
            CodecException.Profile() to "profile",
            CodecException.Mixed() to "mixed",
            CodecException.Duplicate() to "duplicate",
            CodecException.Utf8() to "utf8",
            CodecException.Oversized() to "oversized",
        )
        cases.forEach { (error, reason) ->
            assertEquals(reason, BackupJsonCodec.mapError(error).message)
        }
        assertTrue(BackupJsonCodec.mapError(CodecException.Obsolete()) is ObsoleteDrawerJournalException)
    }

    @Test
    fun limitsMatchRustCodec() {
        assertEquals(2, BackupJsonCodec.VERSION)
        assertEquals(1, BackupJsonCodec.VERSION_V1)
        assertEquals(1024 * 1024, BackupJsonCodec.MAX_BYTES)
        assertEquals("com.caniko.cenix.backup", BackupJsonCodec.FORMAT)
    }
}
