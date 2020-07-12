package com.soywiz.landinger

import java.io.File

fun File.takeIfExists() = takeIf { it.exists() }
