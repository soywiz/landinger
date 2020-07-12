package com.soywiz.landinger.util

import com.soywiz.korio.lang.Cancellable
import java.io.File
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import kotlin.collections.LinkedHashMap

fun File.watchTree(block: (event: WatchEvent<*>) -> Unit): Cancellable {
    var running = true
    val rootFolder = this.absoluteFile
    val watchService = rootFolder.toPath().fileSystem.newWatchService()

    val keys = LinkedHashMap<File, WatchKey>()
    val keysRev = LinkedHashMap<WatchKey, File>()

    fun registerFileOnce(file: File) {
        //println("Register $file...")
        keys.getOrPut(file) {
            file.toPath().register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.OVERFLOW
            ).also {
                keysRev[it] = file
            }
        }
    }

    fun registerFolders() {
        for (sfile in rootFolder.walkTopDown()) {
            if (sfile.isDirectory){
                registerFileOnce(sfile)
            }
        }
    }

    Thread(Runnable {
        println("Watching: $rootFolder")

        registerFolders()

        try {
            while (running) {
                val watchKey = watchService.take() // This call is blocking until events are present
                if (!running) break

                // Last queued event
                if (watchService.poll() == null) {
                    Thread.sleep(10L)
                    if (watchService.poll() == null) {
                        registerFolders()
                    }
                }
                for (event in watchKey.pollEvents()) block(event)
                if (!watchKey.reset()) break
            }
        } finally {
            for (key in keys.values) {
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