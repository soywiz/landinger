package com.soywiz.landinger.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ExecResult(
    val output: String,
    val error: String,
    val exitCode: Int
) {
    val success get() = exitCode == 0
    val outputError: String by lazy { "$output$error" }
    override fun toString(): String = outputError
}

//ssh-keygen -t rsa -b 4096 -f hello2 -q -n id -N ""
suspend fun exec(args: Array<String>, envp: Array<String> = arrayOf(), dir: File = File(".")): ExecResult {
    return withContext(Dispatchers.IO) {
        val proc = Runtime.getRuntime().exec(args, envp, dir)
        ExecResult(
            proc.inputStream.reader().readText(),
            proc.errorStream.reader().readText(),
            proc.waitFor()
        )
    }
}
