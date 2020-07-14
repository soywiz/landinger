package com.soywiz.landinger.util

fun String.canonicalPermalink() = "/" + this.replace("//", "/").trim('/')
//fun String.canonicalPermalink(): String = "/${this.trim('/')}/".replace("//", "/")
