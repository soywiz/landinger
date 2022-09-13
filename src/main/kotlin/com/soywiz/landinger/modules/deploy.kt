package com.soywiz.landinger.modules

import com.soywiz.korinject.AsyncInjector
import com.soywiz.korio.async.AsyncThread
import com.soywiz.korio.file.std.get
import com.soywiz.landinger.Folders
import com.soywiz.landinger.util.exec
import com.soywiz.landinger.util.generateSshRsaKeyPairToFile
import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun Application.installDeploy(injector: AsyncInjector) {
    val folders = injector.get<Folders>()
    val gitAsyncThread = AsyncThread()
    var running = false
    routing {
        route("/__git__") {
            handle {
                if (!running) {
                    gitAsyncThread {
                        try {
                            running = true
                            val output = StringBuilder()
                            try {
                                val gitFolder = folders.content[".gitssh"].absoluteFile
                                val rsaKeyFile = gitFolder["rsakey"]
                                val rsaKeyPubFile = gitFolder["rsakey.pub"]

                                if (!rsaKeyFile.exists()) {
                                    output.append(
                                        generateSshRsaKeyPairToFile(
                                            rsaKeyFile,
                                            comment = "landinger-${System.currentTimeMillis()}"
                                        )
                                    )
                                }

                                val gitExtraEnvs = arrayOf<String>(
                                    "GIT_SSH_COMMAND=ssh -o StrictHostKeyChecking=no -o IdentitiesOnly=yes -i $rsaKeyFile"
                                )

                                output.append(exec(arrayOf("id")))
                                output.append("\n")
                                output.append(withContext(Dispatchers.IO) { rsaKeyPubFile.readText() })
                                output.append("\n")
                                output.append(gitExtraEnvs.joinToString("\n"))
                                output.append("\n")
                                val gitFetch =
                                    exec(arrayOf("git", "fetch", "--all"), gitExtraEnvs, dir = folders.content)
                                output.append(gitFetch)
                                if (gitFetch.success) {
                                    output.append(
                                        exec(
                                            arrayOf("git", "reset", "--hard", "origin/master"),
                                            gitExtraEnvs,
                                            dir = folders.content
                                        )
                                    )
                                }
                            } catch (e: Throwable) {
                                output.append(e.toString())
                            }
                            call.respondText("$output", ContentType.Text.Plain)
                        } finally {
                            running = false
                        }
                    }
                } else {
                    call.respondText("BUSY", ContentType.Text.Plain)
                }
            }
        }
    }
}