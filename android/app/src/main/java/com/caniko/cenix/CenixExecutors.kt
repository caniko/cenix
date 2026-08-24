package com.caniko.cenix

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object CenixExecutors {
    val io: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "cenix-io").apply { isDaemon = true }
    }

    fun io(block: () -> Unit) {
        io.execute(block)
    }
}
