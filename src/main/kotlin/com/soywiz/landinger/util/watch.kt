package com.soywiz.landinger.util

import korlibs.io.lang.*
import io.ktor.util.*
import io.methvin.watcher.*
import java.io.*
import java.nio.file.*
import kotlin.concurrent.*

fun File.watchTreeNew(block: (file: File) -> Unit): Cancellable {
    val path = this.toPath()
    println("Watching path=$path")
    val watcher = DirectoryWatcher.builder()
        .path(path) // or use paths(directoriesToWatch)
        .listener { event ->
            //block(event.rootPath().toFile())
            block(event.path().toFile())
            /*
            val rootFile = event.rootPath().toFile()
            val file = event.path().toFile()
            println("event[${event.eventType()}] : rootPath=${event.rootPath()}, path=${event.path()}")
            when (event.eventType()) {
                DirectoryChangeEvent.EventType.CREATE -> Unit
                DirectoryChangeEvent.EventType.MODIFY -> Unit
                DirectoryChangeEvent.EventType.DELETE -> Unit
            }
            */
        }
        // .fileHashing(false) // defaults to true
        // .logger(logger) // defaults to LoggerFactory.getLogger(DirectoryWatcher.class)
        // .watchService(watchService) // defaults based on OS to either JVM WatchService or the JNA macOS WatchService
        .build()

    thread(start = true, isDaemon = true) {
        watcher.watch()
    }

    return Cancellable { watcher.close() }
}

fun File.watchTree(block: (file: File) -> Unit): Cancellable {
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
            if (sfile.isDirectory) {
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
                //if (watchService.poll() == null) {
                //    Thread.sleep(10L)
                //    if (watchService.poll() == null) {
                //        registerFolders()
                //    }
                //}
                for (event in watchKey.pollEvents()) {
                    //block(keysRev[watchKey], event.context().toString())
                    block(File(keysRev[watchKey], event.context().toString()))
                }
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