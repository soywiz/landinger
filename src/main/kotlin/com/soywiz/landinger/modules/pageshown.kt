package com.soywiz.landinger.modules

import com.soywiz.korinject.Singleton
import com.soywiz.korio.async.Signal
import com.soywiz.landinger.Entry
import io.ktor.application.ApplicationCall
import io.ktor.util.pipeline.PipelineContext

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
