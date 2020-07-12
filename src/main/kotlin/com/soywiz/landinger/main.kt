package com.soywiz.landinger

import com.soywiz.klock.measureTime
import com.soywiz.korio.async.launch
import com.soywiz.korio.file.std.uniVfs
import com.soywiz.korte.*
import com.yahoo.platform.yui.compressor.CssCompressor
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.features.CachingHeaders
import io.ktor.features.PartialContent
import io.ktor.features.XForwardedHeaderSupport
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.content.CachingOptions
import io.ktor.http.content.files
import io.ktor.http.content.static
import io.ktor.http.content.staticRootFolder
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.io.StringWriter
import java.lang.StringBuilder
import kotlin.coroutines.EmptyCoroutineContext

val httpClient = HttpClient(OkHttp)

val docsRoot = File("content").absoluteFile

val luceneIndex: LuceneIndex by lazy {
    LuceneIndex().also { luceneIndex ->
        val indexTime = measureTime {
            luceneIndex.addDocuments(
                *entries.entries.map {
                    LuceneIndex.DocumentInfo(
                        it.permalink,
                        it.title,
                        it.bodyHtml
                    )
                }.toTypedArray()
            )

        }
        println("Lucene indexed in $indexTime...")
    }
}

suspend fun main(args: Array<String>) {
    //luceneIndex.search("hello")

    embeddedServer(Netty, port = 8080) {
        install(XForwardedHeaderSupport)
        install(PartialContent) {
            maxRangeCount = 10
        }
        install(CachingHeaders) {
            options { outgoingContent ->
                val contentType = outgoingContent.contentType?.withoutParameters() ?: ContentType.Any
                when {
                    contentType.match(ContentType.Text.CSS) -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 2 * 60 * 60))
                    contentType.match(ContentType.Image.Any) -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 365 * 24 * 60 * 60))
                    else -> null
                }
            }
        }

        val landing = LandingServing()

        routing {
            route("/") {
                //install(Sessions) {
                //    cookie<UserSession>("SESSION") {
                //        cookie.path = "/"
                //        transform(SessionTransportTransformerEncrypt(SECRET_SESSION_ENCRYPT_KEY, SECRET_SESSION_AUTH_KEY))
                //    }
                //    cookie<LastVisitedPageSession>("LAST_VISITED_PAGE") {
                //        cookie.path = "/"
                //        transform(SessionTransportTransformerEncrypt(SECRET_SESSION_ENCRYPT_KEY, SECRET_SESSION_AUTH_KEY))
                //    }
                //}
                //registerRss()
                //get("/login/oauth/authorize") {
                //    val code = call.request.queryParameters["code"] ?: error("Missing code")
                //    val accessToken = oauthGetAccessToken(code)
                //    val login = getUserLogin(accessToken)
                //    sponsorInfoCache.remove(login)
                //    getCachedSponsor(login)
                //    call.sessions.set(UserSession(login))
                //    call.respondRedirect(call.sessions.get<LastVisitedPageSession>()?.page ?: "/")
                //}
                //route("/__gh_reload") {
                //    handle {
                //        val respond = localVfs(docsRoot).execToString("git", "pull")
                //        entriesReload()
                //        println("__gh_reload: $respond")
                //        //call.respondText(respond)
                //        call.respondRedirect("/")
                //    }
                //}
                //get("/") {
                //    call.serveHome(page = 1)
                //}
                //get("/page/{page}") {
                //    val page = call.parameters["page"]?.toInt() ?: error("Invalid page")
                //    call.serveHome(page = page)
                //}
                //get("/logout") {
                //    call.sessions.clear<UserSession>()
                //    call.respondRedirect(call.sessions.get<LastVisitedPageSession>()?.page ?: "/")
                //}
                get("/") {
                    landing.servePost(call, "")
                }
                get("/{permalink}") {
                    landing.servePost(call, call.parameters["permalink"].toString())
                }
                //get("/i/{path...}") {
                //    val path = call.parameters["path"]
                //    call.respondFile(docsRoot["i"], path!!)
                //}
            }
            for (staticFolder in listOf("i", "img", "content")) {
                static(staticFolder) {
                    staticRootFolder = docsRoot
                    files(staticFolder)
                }
            }
        }
    }.start(wait = true)
}

class LandingServing {
    @Transient
    var config: Map<String, Any?> = mapOf()
    fun reloadConfig() {
        config = yaml.load<Map<String, Any?>>((File("content/config.yml").takeIfExists()?.readText() ?: "").reader())
    }

    val templateProvider = object : TemplateProvider {
        override suspend fun get(template: String): String? {
            val entry = entries[template] ?: return null
            return entry.htmlWithHeader
        }
    }

    class TemplateProviderWithFrontMatter(val path: String) : TemplateProvider {
        override suspend fun get(template: String): String? {
            val fullPath = "$path/$template"
            for (filePath in listOf(fullPath, "$fullPath.md", "$fullPath.html")) {
                val file = File(filePath)
                if (file.exists()) {
                    return FileWithFrontMatter(file).fileContentHtml
                }
            }
            return null
        }
    }


    val layoutsProvider = TemplateProviderWithFrontMatter("content/layouts")
    val includesProvider = TemplateProviderWithFrontMatter("content/includes")

    val templateConfig = TemplateConfig(
        extraTags = listOf(
            Tag("import_css", setOf(), null) {
                //val expr = chunks[0].tag.expr
                val expr = chunks[0].tag.content.trimStart('"').trimEnd('"')
                DefaultBlocks.BlockText(File("content/static/$expr").readText().compressCss())
            }
        )
    )
    val templates = Templates(templateProvider, includesProvider, layoutsProvider, templateConfig, cache = true)

    var doReload = LockSignal()
    init {
        Thread {
            while (true) {
                doReload.wait()
                Thread.sleep(50L)
                println("Reload...")
                entriesReload()
                templates.invalidateCache()
                reloadConfig()
            }
        }.apply { isDaemon = true }.start()
        File("content").watchTree {
            doReload.notifyAll()
        }
        reloadConfig()
    }

    suspend fun servePost(call: ApplicationCall, permalink: String) {
        val entry = templateProvider.get(permalink)
        //println(entries)

        if (entry != null) {
            //call.sessions.set(LastVisitedPageSession(call.request.uri))


            val text = templates.render(permalink, config)
            call.respondText(text, ContentType.Text.Html)

            //call.respondText("page", ContentType.Text.Html)
        } else {
            //println(permalink)
            //println(entries.entries)
            //println(entries.entriesByPermalink)
            call.respondText("404", ContentType.Text.Html)
        }
    }

}

