package com.soywiz.landinger.util

import com.vladsch.flexmark.ext.abbreviation.*
import com.vladsch.flexmark.ext.definition.*
import com.vladsch.flexmark.ext.footnotes.*
import com.vladsch.flexmark.ext.tables.*
import com.vladsch.flexmark.ext.typographic.*
import com.vladsch.flexmark.html.*
import com.vladsch.flexmark.parser.*
import com.vladsch.flexmark.util.data.*
import com.vladsch.flexmark.util.misc.*

private val options = MutableDataSet().also { options ->
    options.setFrom(ParserEmulationProfile.KRAMDOWN)
    //options.setFrom(ParserEmulationProfile.GITHUB)
    options.set(Parser.EXTENSIONS, mutableListOf<Extension>(
        AbbreviationExtension.create(),
        DefinitionExtension.create(),
        FootnoteExtension.create(),
        TablesExtension.create(),
        TypographicExtension.create(),
    ))
}
private val parser = Parser.builder(options).build()
private val renderer = HtmlRenderer.builder(options).build()

fun String.kramdownToHtml() = renderer.render(parser.parse(this))
