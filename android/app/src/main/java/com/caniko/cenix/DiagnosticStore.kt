package com.caniko.cenix

import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

object DiagnosticStore {
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_FILES = 4
    private val dir = AtomicReference<File?>()

    fun init(filesDir: File) {
        dir.set(File(filesDir, "diag"))
    }

    fun append(line: String) {
        val root = dir.get() ?: return
        CenixExecutors.io {
            root.mkdirs()
            val current = File(root, "events.0.log")
            current.appendText(line + "\n")
            if (current.length() > MAX_BYTES) rotate(root)
        }
    }

    fun export(out: OutputStream, identity: Map<String, String>) {
        val root = dir.get()
        out.bufferedWriter().use { writer ->
            identity.forEach { (key, value) -> writer.appendLine("$key=$value") }
            writer.appendLine("---")
            if (root != null) {
                (0 until MAX_FILES)
                    .map { File(root, "events.$it.log") }
                    .filter { it.isFile }
                    .forEach { writer.append(it.readText()) }
            }
        }
    }

    private fun rotate(root: File) {
        File(root, "events.${MAX_FILES - 1}.log").delete()
        for (i in (MAX_FILES - 2) downTo 0) {
            val from = File(root, "events.$i.log")
            if (from.exists()) from.renameTo(File(root, "events.${i + 1}.log"))
        }
    }
}
