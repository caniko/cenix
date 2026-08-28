package com.caniko.cenix

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import com.caniko.cenix.db.CenixDatabase
import com.caniko.cenix.db.RestoreSource
import com.caniko.cenix.uniffi.BackupProfileRef
import com.caniko.cenix.uniffi.ProfileKind
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class CenixBackupAgent : BackupAgent() {
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onFullBackup(output: FullBackupDataOutput) {
        val app = applicationContext as CenixApplication
        if (!app.awaitReady() || app.emergency) throw IOException("launcher is unavailable")
        val database = app.database ?: throw IOException("database is unavailable")
        val profiles = ProfileController(this).also { it.refresh() }.profiles()
            .filter { it.descriptor.kind == ProfileKind.PERSONAL || it.descriptor.kind == ProfileKind.WORK }
            .map { BackupProfileRef(it.descriptor.profileId, it.descriptor.kind) }
        val file = artifact(this)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            BackupJsonCodec.write(
                BackupRepository(database).buildDocument(
                    profiles,
                    includeWork = false,
                    sourceVersion = BuildConfig.VERSION_NAME,
                    sourceCommit = BuildConfig.GIT_COMMIT,
                ),
                stream,
            )
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            file.delete()
            throw IOException("backup export failed", error)
        }
        try {
            val size = file.length()
            if (size > BackupJsonCodec.MAX_BYTES || (output.quota >= 0 && size > output.quota)) {
                onQuotaExceeded(size, output.quota)
                throw IOException("backup quota exceeded")
            }
            val flags = output.transportFlags
            CenixLog.event(
                EventId.BACKUP_EXPORT,
                Severity.INFO,
                mapOf(
                    "encrypted" to (flags and FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED != 0).toString(),
                    "deviceTransfer" to (flags and FLAG_DEVICE_TO_DEVICE_TRANSFER != 0).toString(),
                ),
            )
            fullBackupFile(file, output)
        } finally {
            file.delete()
        }
    }

    override fun onQuotaExceeded(backupDataBytes: Long, quotaBytes: Long) {
        artifact(this).delete()
        CenixLog.event(EventId.BACKUP_EXPORT, Severity.WARN, mapOf("quotaExceeded" to "true"))
    }

    override fun onRestoreFile(
        data: ParcelFileDescriptor,
        size: Long,
        destination: File,
        type: Int,
        mode: Long,
        mtime: Long,
    ) {
        ParcelFileDescriptor.AutoCloseInputStream(data).use { input ->
            if (!acceptsRestore(artifact(this), destination, type, size)) {
                if (size > 0) input.skipNBytes(size)
                return
            }
            val bytes = input.readNBytes(size.toInt())
            if (bytes.size.toLong() != size) throw IOException("truncated backup artifact")
            val atomic = AtomicFile(restoredArtifact(this))
            atomic.baseFile.parentFile?.mkdirs()
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw IOException("restore staging failed", error)
            }
        }
    }

    override fun onRestoreFinished() {
        val app = applicationContext as CenixApplication
        if (!app.awaitReady() || app.emergency) return
        val database = app.database ?: return
        val pending = restoredArtifact(this).isFile
        if (pending && !stageRestoredArtifact(this, database)) throw IOException("invalid backup artifact")
    }

    companion object {
        private const val DIRECTORY = "transport"
        private const val FILE_NAME = "cenix-backup.json"

        fun artifact(context: android.content.Context): File = File(context.filesDir, "$DIRECTORY/$FILE_NAME")

        fun restoredArtifact(context: android.content.Context): File = File(context.noBackupFilesDir, FILE_NAME)

        internal fun acceptsRestore(expected: File, destination: File, type: Int, size: Long): Boolean =
            type == TYPE_FILE && size in 1..BackupJsonCodec.MAX_BYTES.toLong() &&
                destination.canonicalFile == expected.canonicalFile

        fun stageRestoredArtifact(context: android.content.Context, database: CenixDatabase): Boolean {
            val file = restoredArtifact(context)
            if (!file.isFile) return false
            val bytes = file.inputStream().use { it.readNBytes(BackupJsonCodec.MAX_BYTES + 1) }
            if (bytes.isEmpty() || bytes.size > BackupJsonCodec.MAX_BYTES) {
                file.delete()
                return false
            }
            val document = try {
                BackupJsonCodec.read(ByteArrayInputStream(bytes))
            } catch (_: Exception) {
                file.delete()
                return false
            }
            if (document.profiles.any { it.kind != ProfileKind.PERSONAL }) {
                file.delete()
                return false
            }
            val payload = String(bytes, Charsets.UTF_8)
            val repository = BackupRepository(database)
            val generation = checkNotNull(database.dao().workspaceMetadata()).generation
            repository.stage(RestoreSource.SYSTEM, payload, generation, System.currentTimeMillis())
            if (repository.pending()?.phase == com.caniko.cenix.db.RestorePhase.PARSED) repository.queueSystem()
            file.delete()
            CenixLog.event(EventId.BACKUP_RESTORE, Severity.INFO, mapOf("result" to "staged"))
            return true
        }
    }
}
