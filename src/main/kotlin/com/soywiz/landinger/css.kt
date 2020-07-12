package com.soywiz.landinger

import com.yahoo.platform.yui.compressor.CssCompressor
import java.io.StringWriter

fun String.compressCss(): String {
    val cssContent = this
    val out = StringWriter()
    val compressor = CssCompressor(cssContent.reader())
    compressor.compress(out, 0)
    return out.toString()
}
