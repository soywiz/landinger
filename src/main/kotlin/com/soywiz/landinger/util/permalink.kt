package com.soywiz.landinger.util

fun String.canonicalPermalink() = this.trim('/').replace("//", "/")