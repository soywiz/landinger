package com.soywiz.landinger.util

import java.io.File

suspend fun generateSshRsaKeyPairToFile(file: File, size: Int = 4096, comment: String = "id"): ExecResult {
    file.parentFile.mkdirs()
    return exec(arrayOf("ssh-keygen", "-t", "rsa", "-b", "$size", "-f", file.absolutePath, "-q", "-C", comment, "-N", ""))
}
