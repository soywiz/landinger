package com.soywiz.landinger.util

import com.soywiz.korio.lang.Cancellable
import java.io.File
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.util.*

fun File.watchTree(block: (event: WatchEvent<*>) -> Unit): Cancellable {
    var running = true
    val file = this.absoluteFile
    val watchService = file.toPath().fileSystem.newWatchService()

    val keys = arrayListOf<WatchKey>()

    fun registerFile(file: File) {
        //println("Register $file...")
        keys += file.toPath().register(
            watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.OVERFLOW
        )
    }

    Thread(Runnable {
        println("Watching: $file")

        for (sfile in file.walkTopDown()) {
            if (sfile.isDirectory){
                registerFile(sfile)
            }
        }

        try {
            while (running) {
                val watchKey = watchService.take() // This call is blocking until events are present
                if (!running) break
                for (event in watchKey.pollEvents()) block(event)
                if (!watchKey.reset()) break
            }
        } finally {
            for (key in keys) {
                key.cancel()
            }
            watchService.close()
        }

    }, "watchTree").apply {
        isDaemon = true
    }.start()

    return Cancellable {
        running = false
    }
}