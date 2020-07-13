package com.soywiz.landinger.util

fun String.canonicalPermalink() = "/" + this.replace("//", "/").trim('/')