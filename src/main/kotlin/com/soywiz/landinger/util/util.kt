package com.soywiz.landinger.util

import com.soywiz.landinger.*
import io.ktor.application.*
import io.ktor.features.origin
import java.io.File
import java.io.IOException

fun String.absoluteUrl(call: ApplicationCall): String = getAbsoluteUrl(this, call)

fun getAbsoluteUrl(uri: String, call: ApplicationCall): String =
    if (uri.startsWith("http://") || uri.startsWith("https://")) {
        uri
    } else {
        "${call.request.origin.schemePlusHost.trimEnd('/')}/${uri.trimStart('/')}".trimEnd('/')
    }

fun File.takeIfExists() = takeIf { it.exists() }

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
