package com.caniko.cenix

import com.caniko.cenix.uniffi.BackupDocument
import com.caniko.cenix.uniffi.BackupProfileRef
import com.caniko.cenix.uniffi.CodecException
import com.caniko.cenix.uniffi.DrawerBackup
import com.caniko.cenix.uniffi.validateBackupDocument
import java.io.InputStream
import java.io.OutputStream

open class BackupJsonException(message: String) : Exception(message)

// Journals without an explicit version predate the versioned restore format and are
// obsolete development state. Anything versioned but malformed is corruption, not legacy.
class ObsoleteDrawerJournalException : BackupJsonException("obsolete")

object BackupJsonCodec {
    // Artifact parsing, validation, and canonical encoding are canonical in Rust
    // (cenix-core codec module). This object only bounds inputs, forwards bytes,
    // and maps typed failures back to BackupJsonException. There is intentionally
    // no pure-Kotlin parser: backup import already requires native validation,
    // so a fallback could never complete an import in omitNative/emergency mode.
    const val FORMAT = "com.caniko.cenix.backup"
    const val VERSION = 2
    const val VERSION_V1 = 1
    const val MAX_BYTES = 1024 * 1024
    const val MAX_DEPTH = 8
    const val MAX_PAGES = 64
    const val MAX_ITEMS = 4096
    const val MAX_FOLDERS = 512
    const val MAX_MEMBERS = 256
    const val MAX_WIDGETS = 512
    const val MAX_PROFILES = 2
    const val MAX_STRING = 256
    const val MAX_DRAWER_CATEGORIES = 64
    const val MAX_DRAWER_ORDER = 128
    const val MAX_DRAWER_ROWS = 10_000

    fun write(document: BackupDocument, out: OutputStream) {
        out.write(encode(document))
    }

    internal fun encode(document: BackupDocument): ByteArray = try {
        com.caniko.cenix.uniffi.encodeBackupEnvelope(document)
    } catch (e: CodecException) {
        throw mapError(e)
    }

    fun read(input: InputStream): BackupDocument {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) throw BackupJsonException("oversized")
        if (bytes.isEmpty()) throw BackupJsonException("truncated")
        return decode(bytes).also(::validateNative)
    }

    internal fun decode(bytes: ByteArray): BackupDocument = try {
        com.caniko.cenix.uniffi.decodeBackupEnvelope(bytes)
    } catch (e: CodecException) {
        throw mapError(e)
    }

    internal fun encodeDrawerJournal(drawer: DrawerBackup, targets: List<BackupProfileRef>): String = try {
        com.caniko.cenix.uniffi.encodeDrawerJournal(drawer, targets)
    } catch (e: CodecException) {
        throw mapError(e)
    }

    internal fun readDrawerJournal(payload: String): Pair<DrawerBackup, List<BackupProfileRef>> = try {
        val journal = com.caniko.cenix.uniffi.decodeDrawerJournal(payload)
        journal.drawer to journal.targets
    } catch (e: CodecException) {
        throw mapError(e)
    }

    // UniFFI generates empty messages, so the reason travels in the variant name.
    // Only DuplicateKey differs from its reason string ("duplicate-key").
    internal fun mapError(e: CodecException): BackupJsonException {
        if (e is CodecException.Obsolete) return ObsoleteDrawerJournalException()
        val reason = e.javaClass.simpleName.replaceFirstChar { it.lowercase() }
        return BackupJsonException(if (reason == "duplicateKey") "duplicate-key" else reason)
    }

    private fun validateNative(document: BackupDocument) {
        try {
            validateBackupDocument(document)
        } catch (_: com.caniko.cenix.uniffi.BackupException) {
            throw BackupJsonException("semantic")
        }
    }
}
