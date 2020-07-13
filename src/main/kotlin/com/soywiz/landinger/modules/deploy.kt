package com.soywiz.landinger.modules

import com.soywiz.korio.async.AsyncThread
import com.soywiz.korio.file.std.get
import com.soywiz.landinger.Folders
import com.soywiz.landinger.util.exec
import com.soywiz.landinger.util.generateSshRsaKeyPairToFile
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.response.respondText
import io.ktor.routing.Route
import io.ktor.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.installDeploy(folders: Folders) {
    val gitAsyncThread = AsyncThread()
    route("/__git__") {
        handle {
            gitAsyncThread {
                val output = StringBuilder()
                try {
                    val gitFolder = folders.content[".gitssh"]
                    val rsaKeyFile = gitFolder["rsakey"]
                    val rsaKeyPubFile = gitFolder["rsakey.pub"]

                    if (!rsaKeyFile.exists()) {
                        output.append(generateSshRsaKeyPairToFile(rsaKeyFile, comment = "landinger"))
                    }

                    val gitExtraArgs = arrayOf<String>(
                        //"-c", "core.sshCommand=/usr/bin/ssh -i $rsaKeyFile"
                    )
                    val gitExtraEnvs = arrayOf<String>(
                        "GIT_SSH_COMMAND=ssh -o IdentitiesOnly=yes -i $rsaKeyFile"
                    )

                    output.append(withContext(Dispatchers.IO) { rsaKeyPubFile.readText() })
                    val gitFetch =
                        exec(arrayOf("git", "fetch", *gitExtraArgs, "--all"), gitExtraEnvs, folders.content)
                    output.append(gitFetch)
                    if (gitFetch.success) {
                        output.append(
                            exec(
                                arrayOf("git", "reset", *gitExtraArgs, "--hard", "origin/master"),
                                gitExtraEnvs,
                                folders.content
                            )
                        )
                    }
                } catch (e: Throwable) {
                    output.append(e.toString())
                }
                call.respondText("$output", ContentType.Text.Plain)
            }
        }
    }
}