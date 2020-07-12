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
import io.ktor.request.host
import io.ktor.response.respondFile
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.runBlocking
import java.io.File

suspend fun main(args: Array<String>) {
    //luceneIndex.search("hello")
    val params1 = System.getProperty("landinger.args")?.toString()?.let { CliParser.parseString(it) }
    val params2 = args.toList()
    val params = params1 ?: params2
    val cli = CliParser()

    var serve = false
    var generate = false
    var port = System.getenv("VIRTUAL_PORT")?.toIntOrNull() ?: 8080
    var host = "127.0.0.1"
    var contentDir = "content"
    var showHelp = false

    cli.registerSwitch<String>("-c", "--content-dir", desc = "Sets the content directory (default)") { contentDir = it }
    cli.registerSwitch<Boolean>("-s", "--serve", desc = "") { serve = it }
    cli.registerSwitch<String>("-h", "--host", desc = "Sets the host for generating the website") { host = it }
    cli.registerSwitch<Int>("-p", "--port", desc = "Sets the port for listening") { port = it }
    cli.registerSwitch<Boolean>("-g", "--generate", desc = "") { generate = it }
    cli.registerSwitch<Unit>("-h", "--help", desc = "") { showHelp = true }
    cli.registerDefault(desc = "") { error("Unexpected '$it'") }

    cli.parse(params)

    if (showHelp) {
        cli.showHelp()
    } else {
        serve(port)
    }
}

fun serve(port: Int) {
    embeddedServer(Netty, port = port) {
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

fun FileWithFrontMatter.toTemplateContent() = TemplateContent(rawFileContent) {
    when {
        isMarkDown -> it.kramdownToHtml()
        else -> it
    }
}

class LandingServing(val folders: Folders, val entries: Entries) {
    @Transient
    var config: Map<String, Any?> = mapOf()
    fun reloadConfig() {
        config = yaml.load<Map<String, Any?>>((folders.configYml.takeIfExists()?.readText() ?: "").reader())
    }

    val templateProvider = object : NewTemplateProvider {
        override suspend fun newGet(template: String): TemplateContent? {
            val entry = entries.entries[template] ?: return null
            return entry.mfile.toTemplateContent()
        }
    }

    class TemplateProviderWithFrontMatter(val path: File) : NewTemplateProvider {
        override suspend fun newGet(template: String): TemplateContent? {
            //println("INCLUDE: '$template'")
            for (filePath in listOf(template, "$template.md", "$template.html")) {
                val file = path.child(filePath)
                if (file != null && file.exists() && !file.isDirectory) {
                    return FileWithFrontMatter(file).toTemplateContent()
                }
            }
            return null
        }
    }

    val layoutsProvider = TemplateProviderWithFrontMatter(folders.layouts)
    val includesProvider = TemplateProviderWithFrontMatter(folders.includes)

    //fun getAbsoluteUrl(scope: Template.Scope, path: String): String {
    //    return DynamicContext {
    //        val host = runBlocking { scope.get("_request").dynamicGet("host", Mapper2).toDynamicString() }
    //        "https://$host/" + path.toString().trimStart('/')
    //    }
    //}

    fun getAbsoluteUrl(url: String, scope: Template.Scope): String {
        return runBlocking { getAbsoluteUrl(url, scope.get("_call") as ApplicationCall) }
    }

    val templateConfig = TemplateConfig(
        extraTags = listOf(
            Tag("import_css", setOf(), null) {
                //val expr = chunks[0].tag.expr
                val expr = chunks[0].tag.content.trimStart('"').trimEnd('"')
                DefaultBlocks.BlockText(folders.static.child(expr)!!.readText().compressCss())
            }
        ),
        extraFilters = listOf(
            Filter("img_src") {
                val path = subject.toString()
                val absPath = getAbsoluteUrl(path, context.scope)
                absPath
            },
            Filter("img_srcset") {
                val path = subject.toString()
                val absPath = getAbsoluteUrl(path, context.scope)
                args.map { it.toDynamicInt() }.joinToString(", ") { "$absPath ${it}w" }
            },
            Filter("absolute") {
                val call = context.scope.get("_call") as ApplicationCall
                getAbsoluteUrl(subject.toString(), call)
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
        val entry = templateProvider.newGet(permalink)
        if (entry != null) {
            val text = templates.render(permalink, config + mapOf(
                "_request" to mapOf("host" to call.request.host()),
                "_call" to call
            ))
            call.respondText(text, ContentType.Text.Html)
        } else {
            val file = folders.static.child(permalink)
            if (file?.exists() == true) {
                call.respondFile(file)
            }
        }
    }
}
