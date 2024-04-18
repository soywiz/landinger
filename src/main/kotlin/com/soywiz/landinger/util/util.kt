package com.soywiz.landinger.util

import com.soywiz.landinger.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import java.io.File
import java.io.IOException

fun String.absoluteUrl(call: ApplicationCall): String = getAbsoluteUrl(this, call)

fun getAbsoluteUrl(uri: String, call: ApplicationCall?): String =
    when {
        uri.startsWith("http://") || uri.startsWith("https://") -> uri
        call != null -> "${call.request.origin.schemePlusHost.trimEnd('/')}/${uri.trimStart('/')}".trimEnd('/')
        else -> "/${uri.trimStart('/')}"
    }

fun File.takeIfExists(): File? = takeIf { it.exists() }

fun File.child(path: String): File? {
    val folder = this
    val file = File(folder, path);
    return if (!folder.isChild(file)) {
        null
    } else {
        file.canonicalFile
    }
}

fun File.isChild(file: File): Boolean {
    var parent = this
    var f: File?
    try {
        parent = parent.canonicalFile
        f = file.canonicalFile
    } catch (e: IOException) {
        return false
    }
    while (f != null) {
        // equals() only works for paths that are normalized, hence the need for
        // getCanonicalFile() above. "a" isn't equal to "./a", for example.
        if (parent == f) {
            return true
        }
        f = f.parentFile
    }
    return false
}
