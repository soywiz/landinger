package com.soywiz.landinger

import com.soywiz.korio.file.std.get
import com.soywiz.korte.*
import com.soywiz.landinger.modules.MyLuceneIndex
import com.soywiz.landinger.util.*
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CachingHeaders
import io.ktor.features.PartialContent
import io.ktor.features.XForwardedHeaderSupport
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.content.CachingOptions
import io.ktor.response.respondFile
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.pipeline.PipelineContext
import java.io.File

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

        val folders = Folders(File("content"))
        val indexService = IndexService(folders)
        val entries = Entries(folders, indexService)
        val myLuceneIndex = MyLuceneIndex(entries)
        val landing = LandingServing(folders, entries)

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
                    landing.servePost(this, "")
                }
                get("/{permalink}") {
                    landing.servePost(this, call.parameters["permalink"].toString())
                }
            }
        }
    }.start(wait = true)
}

class Folders(content: File) {
    val content = content.canonicalFile
    val layouts = content["layouts"]
    val includes = content["includes"]
    val pages = content["pages"]
    val posts = content["posts"]
    val static = content["static"]
    val configYml = content["config.yml"]
}

class LandingServing(val folders: Folders, val entries: Entries) {
    @Transient
    var config: Map<String, Any?> = mapOf()
    fun reloadConfig() {
        config = yaml.load<Map<String, Any?>>((folders.configYml.takeIfExists()?.readText() ?: "").reader())
    }

    val templateProvider = object : TemplateProvider {
        override suspend fun get(template: String): String? {
            val entry = entries.entries[template] ?: return null
            return entry.htmlWithHeader
        }
    }

    class TemplateProviderWithFrontMatter(val path: File) : TemplateProvider {
        override suspend fun get(template: String): String? {
            for (filePath in listOf(template, "$template.md", "$template.html")) {
                val file = path.child(filePath)
                if (file != null && file.exists()) {
                    return FileWithFrontMatter(file).fileContentHtml
                }
            }
            return null
        }
    }

    val layoutsProvider = TemplateProviderWithFrontMatter(folders.layouts)
    val includesProvider = TemplateProviderWithFrontMatter(folders.includes)

    val templateConfig = TemplateConfig(
        extraTags = listOf(
            Tag("import_css", setOf(), null) {
                //val expr = chunks[0].tag.expr
                val expr = chunks[0].tag.content.trimStart('"').trimEnd('"')
                DefaultBlocks.BlockText(folders.static.child(expr)!!.readText().compressCss())
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
                entries.entriesReload()
                templates.invalidateCache()
                reloadConfig()
            }
        }.apply { isDaemon = true }.start()
        folders.content.watchTree {
            doReload.notifyAll()
        }
        reloadConfig()
    }

    suspend fun servePost(pipeline: PipelineContext<Unit, ApplicationCall>, permalink: String) = pipeline.apply {
        val entry = templateProvider.get(permalink)
        if (entry != null) {
            val text = templates.render(permalink, config)
            call.respondText(text, ContentType.Text.Html)
        } else {
            val file = folders.static.child(permalink)
            if (file?.exists() == true) {
                call.respondFile(file)
            }
        }
    }
}
