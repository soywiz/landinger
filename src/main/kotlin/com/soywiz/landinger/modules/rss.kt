package com.soywiz.landinger.modules

import com.soywiz.klock.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.util.*
import com.soywiz.landinger.Entries
import com.soywiz.landinger.util.absoluteUrl
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*

fun Route.registerRss(entries: Entries) {
    get("/rss") {
        val rootUrl = "/".absoluteUrl(call)
        val rssUrl = "/rss/".absoluteUrl(call)
        val lastUpdated = entries.entries.entries.last().date
        val dateFormat = DateFormat.FORMAT1
        val text = Indenter {
            line("<feed xmlns=\"http://www.w3.org/2005/Atom\">")
            indent {
                line("<link href=\"$rssUrl\" rel=\"self\" type=\"application/atom+xml\"/>")
                line("<link href=\"$rootUrl\" rel=\"alternate\" type=\"text/html\"/>")
                line("<updated>${lastUpdated.format(dateFormat)}</updated>")
                line("<id>$rootUrl</id>")
                line("<title type=\"html\">soywiz</title>")
                line("<subtitle>Let's code it.</subtitle>")
                for (entry in entries.entries.entries.take(10)) {
                    val entryUrl = entry.url(call)
                    line("<entry>")
                    indent {
                        line("<title type=\"html\">${entry.title.escapeHTML()}</title>")
                        line("<link href=\"$entryUrl\" rel=\"alternate\" type=\"text/html\" title=\"${entry.title.escapeHTML()}\"/>")
                        line("<published>${entry.date.format(dateFormat)}</published>")
                        line("<updated>${entry.date.format(dateFormat)}</updated>")
                        line("<id>$entryUrl</id>")
                        line("<content type=\"html\" xml:base=\"https://soywiz.com/job-interviews/\">")
                        line(entry.bodyHtml.substringBefore("<!--more-->").escapeHTML())
                        line("</content>")
                        line("<author><name>soywiz</name></author>")
                        line("<category term=\"applications\"/>")
                        line("<summary type=\"html\">")
                        line(entry.bodyHtml.replace(Regex("\\<.*?\\>"), "").substr(0, 256).escapeHTML())
                        line("</summary>")
                    }
                    line("</entry>")
                }
            }
            line("</feed>")
        }
        //call.respondText(xml.outerXml, ContentType.parse("application/atom+xml"))
        call.respondText(text.toString(), ContentType.Text.Xml)
    }
}