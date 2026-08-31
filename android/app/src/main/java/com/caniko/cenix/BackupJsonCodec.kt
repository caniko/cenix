package com.caniko.cenix

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.caniko.cenix.uniffi.BackupDocument
import com.caniko.cenix.uniffi.BackupException
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
import com.caniko.cenix.uniffi.validateBackupDocument
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class BackupJsonException(message: String) : Exception(message)

object BackupJsonCodec {
    const val FORMAT = "com.caniko.cenix.backup"
    const val VERSION = 1
    const val MAX_BYTES = 1024 * 1024
    const val MAX_DEPTH = 8
    const val MAX_PAGES = 64
    const val MAX_ITEMS = 4096
    const val MAX_FOLDERS = 512
    const val MAX_MEMBERS = 256
    const val MAX_WIDGETS = 512
    const val MAX_PROFILES = 2
    const val MAX_STRING = 256

    private val FORBIDDEN = setOf(
        "generation",
        "appWidgetId",
        "label",
        "labels",
        "icon",
        "icons",
        "intent",
        "intents",
        "uri",
        "url",
        "notification",
        "notifications",
        "wallpaper",
        "diagnostics",
    )

    fun write(document: BackupDocument, out: OutputStream) {
        if (document.formatVersion != VERSION.toUInt()) throw BackupJsonException("version")
        val payload = encodePayload(document)
        val sha = sha256Hex(payload)
        out.write(
            """{"format":"$FORMAT","version":$VERSION,"source":{"version":${quote(document.sourceVersion)},"commit":${quote(document.sourceCommit)}},"payload":"""
                .toByteArray(StandardCharsets.UTF_8),
        )
        out.write(payload)
        out.write((",\"payloadSha256\":\"" + sha + "\"}").toByteArray(StandardCharsets.UTF_8))
    }

    fun read(input: InputStream): BackupDocument = readForTest(input).also(::validateNative)

    internal fun readForTest(input: InputStream): BackupDocument {
        try {
            val bytes = readBounded(input)
            val text = decodeUtf8(bytes)
            val payload = payloadSlice(text)
            var format: String? = null
            var version: Long? = null
            var sourceVersion: String? = null
            var sourceCommit: String? = null
            var sha: String? = null
            var sawPayload = false
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                val seen = HashSet<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    when (val name = uniqueName(reader, seen)) {
                        "format" -> format = boundedString(reader)
                        "version" -> version = exactLong(reader)
                        "source" -> {
                            val source = readSource(reader, 2)
                            sourceVersion = source.first
                            sourceCommit = source.second
                        }
                        "payload" -> {
                            skip(reader, 1)
                            sawPayload = true
                        }
                        "payloadSha256" -> sha = reader.nextString()
                        else -> skipNamed(reader, name, 1)
                    }
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw BackupJsonException("trailing")
            }
            if (format != FORMAT) throw BackupJsonException("format")
            if (version != VERSION.toLong()) throw BackupJsonException("version")
            val srcVersion = sourceVersion
            val srcCommit = sourceCommit
            if (srcVersion.isNullOrEmpty() || srcCommit.isNullOrEmpty()) throw BackupJsonException("source")
            if (!sawPayload) throw BackupJsonException("payload")
            val checksum = sha
            if (checksum == null || checksum.length != 64 || checksum.any { it !in HEX }) throw BackupJsonException("checksum")
            if (sha256Hex(payload) != checksum) throw BackupJsonException("checksum")
            val parsed = JsonReader(StringReader(String(payload, StandardCharsets.UTF_8))).use { reader ->
                reader.isLenient = false
                readPayload(reader, 1, srcVersion, srcCommit)
            }
            validateShape(parsed)
            return parsed
        } catch (e: BackupJsonException) {
            throw e
        } catch (_: Exception) {
            throw BackupJsonException("malformed")
        }
    }

    private val HEX = "0123456789abcdef"

    private fun encodePayload(document: BackupDocument): ByteArray {
        val buf = ByteArrayOutputStream()
        JsonWriter(OutputStreamWriter(buf, StandardCharsets.UTF_8)).use { writer ->
            writer.setLenient(false)
            writePayload(writer, document)
        }
        return buf.toByteArray()
    }

    private fun writePayload(writer: JsonWriter, document: BackupDocument) {
        val pages = document.workspace.pages.sortedWith(compareBy({ it.rank }, { it.pageId }))
        val ranks = pages.associate { it.pageId to it.rank }
        val items = document.workspace.items.sortedWith(
            compareBy<WorkspaceItem> {
                when (it.container) {
                    is ContainerRef.Hotseat -> 0
                    is ContainerRef.Workspace -> 1
                }
            }.thenBy {
                when (val container = it.container) {
                    is ContainerRef.Hotseat -> 0
                    is ContainerRef.Workspace -> ranks[container.pageId] ?: Int.MAX_VALUE
                }
            }.thenBy { it.cell.cellY }.thenBy { it.cell.cellX }.thenBy { it.itemId },
        )
        val folders = document.workspace.folders
            .map { folder ->
                Folder(
                    folder.folderId,
                    folder.title,
                    folder.members.sortedWith(compareBy({ it.rank }, { it.itemId })),
                )
            }
            .sortedBy { it.folderId }
        val profiles = document.profiles.sortedBy { it.profileId }
        val widgets = document.widgets.sortedBy { it.itemId }
        writer.beginObject()
        writer.name("settings")
        writeSettings(writer, document.settings)
        writer.name("profiles")
        writer.beginArray()
        for (profile in profiles) writeProfile(writer, profile)
        writer.endArray()
        writer.name("workspace")
        writer.beginObject()
        writer.name("grid")
        writeGrid(writer, document.workspace.grid)
        writer.name("pages")
        writer.beginArray()
        for (page in pages) writePage(writer, page)
        writer.endArray()
        writer.name("items")
        writer.beginArray()
        for (item in items) writeItem(writer, item)
        writer.endArray()
        writer.name("folders")
        writer.beginArray()
        for (folder in folders) writeFolder(writer, folder)
        writer.endArray()
        writer.endObject()
        writer.name("widgets")
        writer.beginArray()
        for (widget in widgets) writeWidget(writer, widget)
        writer.endArray()
        writer.name("nextItemId").value(u64(document.nextItemId))
        writer.name("nextPageId").value(u64(document.nextPageId))
        writer.endObject()
    }

    private fun writeSettings(writer: JsonWriter, settings: BackupSettings) {
        writer.beginObject()
        writer.name("gridName").value(settings.gridName)
        writer.name("notificationDots").value(settings.notificationDots)
        writer.name("themedIcons").value(settings.themedIcons)
        writer.name("autoAddApps").value(settings.autoAddApps)
        writer.endObject()
    }

    private fun writeProfile(writer: JsonWriter, profile: BackupProfileRef) {
        writer.beginObject()
        writer.name("profileId").value(u64(profile.profileId))
        writer.name("kind").value(
            when (profile.kind) {
                ProfileKind.PERSONAL -> "personal"
                ProfileKind.WORK -> "work"
                ProfileKind.PRIVATE, ProfileKind.OTHER -> throw BackupJsonException("profile")
            },
        )
        writer.endObject()
    }

    private fun writeGrid(writer: JsonWriter, grid: GridSpec) {
        writer.beginObject()
        writer.name("cols").value(grid.cols.toLong())
        writer.name("rows").value(grid.rows.toLong())
        writer.name("hotseatCols").value(grid.hotseatCols.toLong())
        writer.endObject()
    }

    private fun writePage(writer: JsonWriter, page: WorkspacePage) {
        writer.beginObject()
        writer.name("pageId").value(u64(page.pageId))
        writer.name("rank").value(page.rank.toLong())
        writer.endObject()
    }

    private fun writeItem(writer: JsonWriter, item: WorkspaceItem) {
        writer.beginObject()
        writer.name("itemId").value(u64(item.itemId))
        writer.name("payload")
        writePayloadKind(writer, item.payload)
        writer.name("container")
        writeContainer(writer, item.container)
        writer.name("cell")
        writeCell(writer, item.cell)
        writer.endObject()
    }

    private fun writeFolder(writer: JsonWriter, folder: Folder) {
        writer.beginObject()
        writer.name("folderId").value(u64(folder.folderId))
        writer.name("title").value(folder.title)
        writer.name("members")
        writer.beginArray()
        for (member in folder.members) {
            writer.beginObject()
            writer.name("itemId").value(u64(member.itemId))
            writer.name("payload")
            writePayloadKind(writer, member.payload)
            writer.name("rank").value(u64(member.rank.toULong()))
            writer.endObject()
        }
        writer.endArray()
        writer.endObject()
    }

    private fun writeWidget(writer: JsonWriter, widget: BackupWidgetMetadata) {
        writer.beginObject()
        writer.name("itemId").value(u64(widget.itemId))
        writer.name("minSpanX").value(widget.minSpanX.toLong())
        writer.name("minSpanY").value(widget.minSpanY.toLong())
        writer.name("resizeX").value(widget.resizeX.toLong())
        writer.name("resizeY").value(widget.resizeY.toLong())
        writer.endObject()
    }

    private fun writePayloadKind(writer: JsonWriter, payload: ItemPayload) {
        writer.beginObject()
        when (payload) {
            is ItemPayload.Application -> {
                writer.name("kind").value("application")
                writer.name("package").value(payload.component.`package`)
                writer.name("class").value(payload.component.`class`)
                writer.name("profileId").value(u64(payload.component.profileId))
            }
            is ItemPayload.Folder -> writer.name("kind").value("folder")
            is ItemPayload.Shortcut -> {
                writer.name("kind").value("shortcut")
                writer.name("package").value(payload.shortcut.`package`)
                writer.name("shortcutId").value(payload.shortcut.shortcutId)
                writer.name("profileId").value(u64(payload.shortcut.profileId))
            }
            is ItemPayload.Widget -> {
                writer.name("kind").value("widget")
                writer.name("package").value(payload.provider.`package`)
                writer.name("class").value(payload.provider.`class`)
                writer.name("profileId").value(u64(payload.provider.profileId))
            }
        }
        writer.endObject()
    }

    private fun writeContainer(writer: JsonWriter, container: ContainerRef) {
        writer.beginObject()
        when (container) {
            is ContainerRef.Workspace -> {
                writer.name("kind").value("workspace")
                writer.name("pageId").value(u64(container.pageId))
            }
            is ContainerRef.Hotseat -> writer.name("kind").value("hotseat")
        }
        writer.endObject()
    }

    private fun writeCell(writer: JsonWriter, cell: CellRect) {
        writer.beginObject()
        writer.name("cellX").value(cell.cellX.toLong())
        writer.name("cellY").value(cell.cellY.toLong())
        writer.name("spanX").value(cell.spanX.toLong())
        writer.name("spanY").value(cell.spanY.toLong())
        writer.endObject()
    }

    private fun readSource(reader: JsonReader, depth: Int): Pair<String, String> {
        checkDepth(depth)
        var version: String? = null
        var commit: String? = null
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = uniqueName(reader, seen)) {
                "version" -> version = boundedString(reader)
                "commit" -> commit = boundedString(reader)
                else -> skipNamed(reader, name, depth)
            }
        }
        reader.endObject()
        return (version ?: throw BackupJsonException("source")) to (commit ?: throw BackupJsonException("source"))
    }

    private fun readPayload(reader: JsonReader, depth: Int, sourceVersion: String, sourceCommit: String): BackupDocument {
        checkDepth(depth)
        var settings: BackupSettings? = null
        var profiles: List<BackupProfileRef>? = null
        var workspace: WorkspaceSnapshot? = null
        var widgets: List<BackupWidgetMetadata>? = null
        var nextItemId: ULong? = null
        var nextPageId: ULong? = null
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = uniqueName(reader, seen)) {
                "settings" -> settings = readSettings(reader, depth + 1)
                "profiles" -> profiles = readList(reader, depth + 1, MAX_PROFILES) { readProfile(it, depth + 2) }
                "workspace" -> workspace = readWorkspace(reader, depth + 1)
                "widgets" -> widgets = readList(reader, depth + 1, MAX_WIDGETS) { readWidget(it, depth + 2) }
                "nextItemId" -> nextItemId = exactU64(reader)
                "nextPageId" -> nextPageId = exactU64(reader)
                else -> skipNamed(reader, name, depth)
            }
        }
        reader.endObject()
        return BackupDocument(
            VERSION.toUInt(),
            sourceVersion,
            sourceCommit,
            settings ?: throw BackupJsonException("settings"),
            profiles ?: throw BackupJsonException("profiles"),
            workspace ?: throw BackupJsonException("workspace"),
            widgets ?: throw BackupJsonException("widgets"),
            nextItemId ?: throw BackupJsonException("allocator"),
            nextPageId ?: throw BackupJsonException("allocator"),
        )
    }

    private fun readSettings(reader: JsonReader, depth: Int): BackupSettings {
        checkDepth(depth)
        var gridName: String? = null
        var notificationDots: Boolean? = null
        var themedIcons: Boolean? = null
        var autoAddApps: Boolean? = null
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = uniqueName(reader, seen)) {
                "gridName" -> gridName = boundedString(reader)
                "notificationDots" -> notificationDots = reader.nextBoolean()
                "themedIcons" -> themedIcons = reader.nextBoolean()
                "autoAddApps" -> autoAddApps = reader.nextBoolean()
                else -> skipNamed(reader, name, depth)
            }
        }
        reader.endObject()
        return BackupSettings(
            gridName ?: throw BackupJsonException("settings"),
            notificationDots ?: throw BackupJsonException("settings"),
            themedIcons ?: throw BackupJsonException("settings"),
            autoAddApps ?: throw BackupJsonException("settings"),
        )
    }

    private fun readProfile(reader: JsonReader, depth: Int): BackupProfileRef {
        checkDepth(depth)
        var profileId: ULong? = null
        var kind: ProfileKind? = null
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = uniqueName(reader, seen)) {
                "profileId" -> profileId = exactU64(reader)
                "kind" -> kind = when (val raw = boundedString(reader)) {
                    "personal" -> ProfileKind.PERSONAL
                    "work" -> ProfileKind.WORK
                    "private" -> ProfileKind.PRIVATE
                    else -> throw BackupJsonException("profile")
                }
                else -> skipNamed(reader, name, depth)
            }
        }
        reader.endObject()
        return BackupProfileRef(
            profileId ?: throw BackupJsonException("profile"),
            kind ?: throw BackupJsonException("profile"),
        )
    }

    private fun readWorkspace(reader: JsonReader, depth: Int): WorkspaceSnapshot {
        checkDepth(depth)
        var grid: GridSpec? = null
        var pages: List<WorkspacePage>? = null
        var items: List<WorkspaceItem>? = null
        var folders: List<Folder>? = null
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = uniqueName(reader, seen)) {
                "grid" -> grid = readGrid(reader, depth + 1)
                "pages" -> pages = readList(reader, depth + 1, MAX_PAGES) { readPage(it, depth + 2) }
                "items" -> items = readList(reader, depth + 1, MAX_ITEMS) { readItem(it, depth + 2) }
                "folders" -> folders = readList(reader, depth + 1, MAX_FOLDERS) { readFolder(it, depth + 2) }
                else -> skipNamed(reader, name, depth)
            }
        }
        reader.endObject()
        return WorkspaceSnapshot(
            0uL,
            grid ?: throw BackupJsonException("grid"),
            pages ?: throw BackupJsonException("pages"),
            items ?: throw BackupJsonException("items"),
            folders ?: throw BackupJsonException("folders"),
        )
    }

    private fun readGrid(reader: JsonReader, depth: Int): GridSpec {
        checkDepth(depth)
        var cols: Int? = null
        var rows: Int? = null
        var hotseatCols: Int? = null
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = uniqueName(reader, seen)) {
                "cols" -> cols = exactInt(reader)
                "rows" -> rows = exactInt(reader)
                "hotseatCols" -> hotseatCols = exactInt(reader)
                else -> skipNamed(reader, name, depth)
            }
        }
        reader.endObject()
        return GridSpec(
            cols ?: throw BackupJsonException("grid"),
            rows ?: throw BackupJsonException("grid"),
            hotseatCols ?: throw BackupJsonException("grid"),
        )
    }

    private fun readPage(reader: JsonReader, depth: Int): WorkspacePage {
        checkDepth(depth)
        var pageId: ULong? = null
        var rank: Int? = null
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = uniqueName(reader, seen)) {
                "pageId" -> pageId = exactU64(reader)
                "rank" -> rank = exactInt(reader)
                else -> skipNamed(reader, name, depth)
            }
        }
        reader.endObject()
        return WorkspacePage(
            pageId ?: throw BackupJsonException("page"),
            rank ?: throw BackupJsonException("page"),
        )
    }

    private fun readItem(reader: JsonReader, depth: Int): WorkspaceItem {
        checkDepth(depth)
        var itemId: ULong? = null
        var payload: ItemPayload? = null
        var container: ContainerRef? = null
        var cell: CellRect? = null
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = uniqueName(reader, seen)) {
                "itemId" -> itemId = exactU64(reader)
                "payload" -> payload = readPayloadKind(reader, depth + 1)
                "container" -> container = readContainer(reader, depth + 1)
                "cell" -> cell = readCell(reader, depth + 1)
                else -> skipNamed(reader, name, depth)
            }
        }
        reader.endObject()
        return WorkspaceItem(
            itemId ?: throw BackupJsonException("item"),
            payload ?: throw BackupJsonException("item"),
            container ?: throw BackupJsonException("item"),
            cell ?: throw BackupJsonException("item"),
        )
    }

    private fun readFolder(reader: JsonReader, depth: Int): Folder {
        checkDepth(depth)
        var folderId: ULong? = null
        var title: String? = null
        var members: List<FolderMember>? = null
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = uniqueName(reader, seen)) {
                "folderId" -> folderId = exactU64(reader)
                "title" -> title = boundedString(reader)
                "members" -> members = readList(reader, depth + 1, MAX_MEMBERS) { readMember(it, depth + 2) }
                else -> skipNamed(reader, name, depth)
            }
        }
        reader.endObject()
        return Folder(
            folderId ?: throw BackupJsonException("folder"),
            title ?: throw BackupJsonException("folder"),
            members ?: throw BackupJsonException("folder"),
        )
    }

    private fun readMember(reader: JsonReader, depth: Int): FolderMember {
        checkDepth(depth)
        var itemId: ULong? = null
        var payload: ItemPayload? = null
        var rank: UInt? = null
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = uniqueName(reader, seen)) {
                "itemId" -> itemId = exactU64(reader)
                "payload" -> payload = readPayloadKind(reader, depth + 1)
                "rank" -> {
                    val value = exactU64(reader)
                    if (value > UInt.MAX_VALUE.toULong()) throw BackupJsonException("integer")
                    rank = value.toUInt()
                }
                else -> skipNamed(reader, name, depth)
            }
        }
        reader.endObject()
        return FolderMember(
            itemId ?: throw BackupJsonException("member"),
            payload ?: throw BackupJsonException("member"),
            rank ?: throw BackupJsonException("member"),
        )
    }

    private fun readWidget(reader: JsonReader, depth: Int): BackupWidgetMetadata {
        checkDepth(depth)
        var itemId: ULong? = null
        var minSpanX: Int? = null
        var minSpanY: Int? = null
        var resizeX: Int? = null
        var resizeY: Int? = null
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = uniqueName(reader, seen)) {
                "itemId" -> itemId = exactU64(reader)
                "minSpanX" -> minSpanX = exactInt(reader)
                "minSpanY" -> minSpanY = exactInt(reader)
                "resizeX" -> resizeX = exactInt(reader)
                "resizeY" -> resizeY = exactInt(reader)
                else -> skipNamed(reader, name, depth)
            }
        }
        reader.endObject()
        return BackupWidgetMetadata(
            itemId ?: throw BackupJsonException("widget"),
            minSpanX ?: throw BackupJsonException("widget"),
            minSpanY ?: throw BackupJsonException("widget"),
            resizeX ?: throw BackupJsonException("widget"),
            resizeY ?: throw BackupJsonException("widget"),
        )
    }

    private fun readPayloadKind(reader: JsonReader, depth: Int): ItemPayload {
        checkDepth(depth)
        var kind: String? = null
        var pkg: String? = null
        var cls: String? = null
        var shortcutId: String? = null
        var profileId: ULong? = null
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = uniqueName(reader, seen)) {
                "kind" -> kind = boundedString(reader)
                "package" -> pkg = boundedString(reader)
                "class" -> cls = boundedString(reader)
                "shortcutId" -> shortcutId = boundedString(reader)
                "profileId" -> profileId = exactU64(reader)
                else -> skipNamed(reader, name, depth)
            }
        }
        reader.endObject()
        return when (kind) {
            "application" -> ItemPayload.Application(
                ComponentId(
                    pkg ?: throw BackupJsonException("kind"),
                    cls ?: throw BackupJsonException("kind"),
                    profileId ?: throw BackupJsonException("kind"),
                ),
            )
            "folder" -> ItemPayload.Folder
            "shortcut" -> ItemPayload.Shortcut(
                ShortcutId(
                    pkg ?: throw BackupJsonException("kind"),
                    shortcutId ?: throw BackupJsonException("kind"),
                    profileId ?: throw BackupJsonException("kind"),
                ),
            )
            "widget" -> ItemPayload.Widget(
                WidgetProviderId(
                    pkg ?: throw BackupJsonException("kind"),
                    cls ?: throw BackupJsonException("kind"),
                    profileId ?: throw BackupJsonException("kind"),
                ),
            )
            else -> throw BackupJsonException("kind")
        }
    }

    private fun readContainer(reader: JsonReader, depth: Int): ContainerRef {
        checkDepth(depth)
        var kind: String? = null
        var pageId: ULong? = null
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = uniqueName(reader, seen)) {
                "kind" -> kind = boundedString(reader)
                "pageId" -> pageId = exactU64(reader)
                else -> skipNamed(reader, name, depth)
            }
        }
        reader.endObject()
        return when (kind) {
            "workspace" -> ContainerRef.Workspace(pageId ?: throw BackupJsonException("container"))
            "hotseat" -> ContainerRef.Hotseat
            else -> throw BackupJsonException("container")
        }
    }

    private fun readCell(reader: JsonReader, depth: Int): CellRect {
        checkDepth(depth)
        var cellX: Int? = null
        var cellY: Int? = null
        var spanX: Int? = null
        var spanY: Int? = null
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = uniqueName(reader, seen)) {
                "cellX" -> cellX = exactInt(reader)
                "cellY" -> cellY = exactInt(reader)
                "spanX" -> spanX = exactInt(reader)
                "spanY" -> spanY = exactInt(reader)
                else -> skipNamed(reader, name, depth)
            }
        }
        reader.endObject()
        return CellRect(
            cellX ?: throw BackupJsonException("cell"),
            cellY ?: throw BackupJsonException("cell"),
            spanX ?: throw BackupJsonException("cell"),
            spanY ?: throw BackupJsonException("cell"),
        )
    }

    private fun <T> readList(reader: JsonReader, depth: Int, max: Int, readOne: (JsonReader) -> T): List<T> {
        checkDepth(depth)
        reader.beginArray()
        val out = ArrayList<T>()
        while (reader.hasNext()) {
            if (out.size >= max) throw BackupJsonException("count")
            out.add(readOne(reader))
        }
        reader.endArray()
        return out
    }

    private fun uniqueName(reader: JsonReader, seen: MutableSet<String>): String =
        reader.nextName().also { if (!seen.add(it)) throw BackupJsonException("duplicate-key") }

    private fun skipNamed(reader: JsonReader, name: String, depth: Int) {
        if (name in FORBIDDEN) throw BackupJsonException("forbidden")
        skip(reader, depth)
    }

    private fun skip(reader: JsonReader, depth: Int) {
        var current = depth
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                current++
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                current++
            }
            JsonToken.END_DOCUMENT, JsonToken.END_OBJECT, JsonToken.END_ARRAY, JsonToken.NAME ->
                throw BackupJsonException("token")
            else -> {
                reader.skipValue()
                return
            }
        }
        checkDepth(current)
        val start = depth
        while (current > start) {
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT, JsonToken.BEGIN_ARRAY -> {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) reader.beginObject() else reader.beginArray()
                    current++
                    checkDepth(current)
                }
                JsonToken.END_OBJECT -> {
                    reader.endObject()
                    current--
                }
                JsonToken.END_ARRAY -> {
                    reader.endArray()
                    current--
                }
                JsonToken.NAME -> skipNamed(reader, reader.nextName(), current)
                JsonToken.END_DOCUMENT -> throw BackupJsonException("truncated")
                else -> reader.skipValue()
            }
        }
    }

    private fun validateNative(document: BackupDocument) {
        try {
            validateBackupDocument(document)
        } catch (_: BackupException) {
            throw BackupJsonException("semantic")
        }
    }

    private fun validateShape(document: BackupDocument) {
        val profiles = document.profiles.sortedBy { it.profileId }
        if (
            profiles.isEmpty() || profiles.size > MAX_PROFILES ||
            profiles[0] != BackupProfileRef(0uL, ProfileKind.PERSONAL) ||
            (profiles.size == 2 && profiles[1] != BackupProfileRef(1uL, ProfileKind.WORK))
        ) {
            throw BackupJsonException("profile")
        }
        val allowedProfiles = profiles.mapTo(HashSet()) { it.profileId }
        val ids = HashSet<ULong>()
        fun recordId(value: ULong) {
            if (value == 0uL || value >= Long.MAX_VALUE.toULong() || !ids.add(value)) {
                throw BackupJsonException("duplicate")
            }
        }
        for (item in document.workspace.items) {
            recordId(item.itemId)
            if (item.cell.spanX <= 0 || item.cell.spanY <= 0) throw BackupJsonException("span")
            if (item.payload is ItemPayload.Widget && item.container is ContainerRef.Hotseat) {
                throw BackupJsonException("widget")
            }
            profileOf(item.payload)?.let { if (it !in allowedProfiles) throw BackupJsonException("profile") }
        }
        for (folder in document.workspace.folders) {
            var profile: ULong? = null
            for (member in folder.members) {
                recordId(member.itemId)
                if (member.payload is ItemPayload.Widget || member.payload is ItemPayload.Folder) {
                    throw BackupJsonException("folder")
                }
                val next = profileOf(member.payload) ?: throw BackupJsonException("folder")
                if (next !in allowedProfiles) throw BackupJsonException("profile")
                if (profile != null && profile != next) throw BackupJsonException("mixed")
                profile = next
            }
        }
        val widgetItems = document.workspace.items
            .filter { it.payload is ItemPayload.Widget }
            .associateBy { it.itemId }
        if (
            document.widgets.map { it.itemId }.toSet().size != document.widgets.size ||
            document.widgets.map { it.itemId }.toSet() != widgetItems.keys
        ) {
            throw BackupJsonException("widget")
        }
        for (widget in document.widgets) {
            val item = widgetItems.getValue(widget.itemId)
            if (
                widget.minSpanX <= 0 || widget.minSpanY <= 0 ||
                widget.resizeX < widget.minSpanX || widget.resizeY < widget.minSpanY ||
                widget.minSpanX > item.cell.spanX || widget.minSpanY > item.cell.spanY
            ) {
                throw BackupJsonException("widget")
            }
        }
        for (index in document.workspace.items.indices) {
            val left = document.workspace.items[index]
            if (document.workspace.items.subList(index + 1, document.workspace.items.size).any { right ->
                    left.container == right.container && overlap(left.cell, right.cell)
                }
            ) {
                throw BackupJsonException("overlap")
            }
        }
    }

    private fun profileOf(payload: ItemPayload): ULong? = when (payload) {
        is ItemPayload.Application -> payload.component.profileId
        is ItemPayload.Shortcut -> payload.shortcut.profileId
        is ItemPayload.Widget -> payload.provider.profileId
        is ItemPayload.Folder -> null
    }

    private fun overlap(a: CellRect, b: CellRect): Boolean {
        val aRight = a.cellX.toLong() + a.spanX.toLong()
        val bRight = b.cellX.toLong() + b.spanX.toLong()
        val aBottom = a.cellY.toLong() + a.spanY.toLong()
        val bBottom = b.cellY.toLong() + b.spanY.toLong()
        return a.cellX.toLong() < bRight && b.cellX.toLong() < aRight &&
            a.cellY.toLong() < bBottom && b.cellY.toLong() < aBottom
    }

    private fun readBounded(input: InputStream): ByteArray {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(tmp)
            if (n < 0) break
            if (n > MAX_BYTES - total) throw BackupJsonException("oversized")
            total += n
            buf.write(tmp, 0, n)
        }
        if (total == 0) throw BackupJsonException("truncated")
        return buf.toByteArray()
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw BackupJsonException("utf8")
        }
    }

    private fun payloadSlice(text: String): ByteArray {
        var i = 0
        var inString = false
        var escape = false
        var depth = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inString = false
                i++
                continue
            }
            if (c == '"') {
                if (depth == 1 && text.startsWith("\"payload\"", i)) {
                    var j = i + 9
                    while (j < text.length && text[j] <= ' ') j++
                    if (j < text.length && text[j] == ':') {
                        j++
                        while (j < text.length && text[j] <= ' ') j++
                        if (j >= text.length || text[j] != '{') throw BackupJsonException("payload")
                        return text.substring(j, objectEnd(text, j)).toByteArray(StandardCharsets.UTF_8)
                    }
                }
                inString = true
            } else if (c == '{' || c == '[') {
                depth++
                checkDepth(depth)
            } else if (c == '}' || c == ']') {
                depth--
                if (depth < 0) throw BackupJsonException("malformed")
            }
            i++
        }
        throw BackupJsonException("payload")
    }

    private fun objectEnd(text: String, start: Int): Int {
        var depth = 0
        var i = start
        var inString = false
        var escape = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        checkDepth(depth)
                    }
                    '}' -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                    ']' -> depth--
                }
            }
            i++
        }
        throw BackupJsonException("truncated")
    }

    private fun boundedString(reader: JsonReader): String {
        if (reader.peek() != JsonToken.STRING) throw BackupJsonException("string")
        val value = reader.nextString()
        if (value.codePointCount(0, value.length) > MAX_STRING || value.any { it.isISOControl() }) {
            throw BackupJsonException("string")
        }
        return value
    }

    private fun exactLong(reader: JsonReader): Long {
        if (reader.peek() != JsonToken.NUMBER) throw BackupJsonException("integer")
        val raw = reader.nextString()
        if (raw.isEmpty() || raw.any { it != '-' && it !in '0'..'9' } || raw == "-" || (raw.length > 1 && raw[0] == '0') || (raw.startsWith("-0") && raw.length > 2)) {
            throw BackupJsonException("integer")
        }
        return raw.toLongOrNull() ?: throw BackupJsonException("integer")
    }

    private fun exactInt(reader: JsonReader): Int {
        val value = exactLong(reader)
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw BackupJsonException("integer")
        return value.toInt()
    }

    private fun exactU64(reader: JsonReader): ULong {
        val value = exactLong(reader)
        if (value < 0 || value == Long.MAX_VALUE) throw BackupJsonException("integer")
        return value.toULong()
    }

    private fun u64(value: ULong): Long {
        if (value >= Long.MAX_VALUE.toULong()) throw BackupJsonException("integer")
        return value.toLong()
    }

    private fun checkDepth(depth: Int) {
        if (depth > MAX_DEPTH) throw BackupJsonException("depth")
    }

    private fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val b = digest[i].toInt() and 0xff
            out[i * 2] = HEX[b ushr 4]
            out[i * 2 + 1] = HEX[b and 0x0f]
        }
        return String(out)
    }
}
