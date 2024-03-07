package com.soywiz.landinger.modules

import korlibs.inject.*
import korlibs.io.async.*
import com.soywiz.landinger.*
import io.ktor.server.application.*

@Singleton
class PageShownBus {
    val pageShown = Signal<Page>()
    //data class Page(val context: PipelineContext<Unit, ApplicationCall>, val entry: Entry?)
    data class Page(val call: ApplicationCall, val entry: Entry?, val permalink: String) {
        var logged = false
        var isSponsor = false
        val extraConfig = LinkedHashMap<String, Any?>()
    }
}
