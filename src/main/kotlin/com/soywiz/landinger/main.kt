package com.soywiz.landinger

import com.soywiz.kds.linkedHashMapOf
import com.soywiz.korio.async.AsyncThread
import com.soywiz.korio.file.std.get
import com.soywiz.korio.lang.substr
import com.soywiz.korte.*
import com.soywiz.landinger.modules.MyLuceneIndex
import com.soywiz.landinger.util.*
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.*
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.CachingOptions
import io.ktor.request.host
import io.ktor.request.uri
import io.ktor.response.respondFile
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.pipeline.PipelineContext
import jdk.nashorn.internal.objects.NativeRegExp.exec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.text.DateFormat
import java.util.*
import kotlin.collections.LinkedHashMap

suspend fun main(args: Array<String>) {
    //luceneIndex.search("hello")
    val params1 = System.getProperty("landinger.args")?.toString()?.let { CliParser.parseString(it) }
    val params2 = args.toList()
    val params = params1 ?: params2
    val cli = CliParser()

    var config = Config()
    var serve = false
    var generate = false
    var showHelp = false

    cli.registerSwitch<String>("-c", "--content-dir", desc = "Sets the content directory (default)") { config.contentDir = it }
    cli.registerSwitch<Boolean>("-s", "--serve", desc = "") { serve = it }
    cli.registerSwitch<String>("-h", "--host", desc = "Sets the host for generating the website") { config.host = it }
    cli.registerSwitch<Int>("-p", "--port", desc = "Sets the port for listening") { config.port = it }
    cli.registerSwitch<Boolean>("-g", "--generate", desc = "") { generate = it }
    cli.registerSwitch<Unit>("-h", "--help", desc = "") { showHelp = true }
    cli.registerDefault(desc = "") { error("Unexpected '$it'") }

    cli.parse(params)

    if (showHelp) {
        cli.showHelp()
    } else {
        serve(config)
    }
}

data class Config(
    var port: Int = System.getenv("VIRTUAL_PORT")?.toIntOrNull() ?: 8080,
    var host: String = "127.0.0.1",
    var contentDir: String = "content"
)

fun serve(config: Config) {
    embeddedServer(Netty, port = config.port) {
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

        val folders = Folders(File(config.contentDir))
        val indexService = IndexService(folders)
        val entries = Entries(folders, indexService)
        val myLuceneIndex = MyLuceneIndex(entries)
        val landing = LandingServing(folders, entries)
        val gitAsyncThread = AsyncThread()

        routing {
            install(StatusPages) {
                exception<NotFoundException> { cause ->
                    landing.serveEntry("/404", call, cause, code = HttpStatusCode.NotFound)
                }
            }
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
                route("/__git__") {
                    handle {
                        gitAsyncThread {
                            val output = StringBuilder()
                            try {
                                val gitFolder = folders.content[".gitssh"]
                                val rsaKeyFile = gitFolder["rsakey"]
                                val rsaKeyPubFile = gitFolder["rsakey.pub"]

                                if (!rsaKeyFile.exists()) {
                                    output.append(generateSshRsaKeyPairToFile(rsaKeyFile, comment = "landinger"))
                                }

                                val gitExtraArgs = arrayOf<String>(
                                    //"-c", "core.sshCommand=/usr/bin/ssh -i $rsaKeyFile"
                                )
                                val gitExtraEnvs = arrayOf<String>(
                                    "GIT_SSH_COMMAND=ssh -o IdentitiesOnly=yes -i $rsaKeyFile"
                                )

                                output.append(withContext(Dispatchers.IO) { rsaKeyPubFile.readText() })
                                val gitFetch =
                                    exec(arrayOf("git", "fetch", *gitExtraArgs, "--all"), gitExtraEnvs, folders.content)
                                output.append(gitFetch)
                                if (gitFetch.success) {
                                    output.append(
                                        exec(
                                            arrayOf("git", "reset", *gitExtraArgs, "--hard", "origin/master"),
                                            gitExtraEnvs,
                                            folders.content
                                        )
                                    )
                                }
                            } catch (e: Throwable) {
                                output.append(e.toString())
                            }
                            call.respondText("$output", ContentType.Text.Plain)
                        }
                    }
                }
                get("/") {
                    landing.servePost(this, "")
                }
                get("/{permalink...}") {
                    landing.servePost(this, call.request.uri)
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
    val data = content["data"]
    val posts = content["posts"]
    val collections = content["collections"]
    val static = content["static"]
    val configYml = content["config.yml"]
}

fun FileWithFrontMatter.toTemplateContent() = TemplateContent(rawFileContent, when {
    isMarkDown -> "markdown"
    isXml -> "xml"
    else -> null
})

/*
fun FileWithFrontMatter.toTemplateContent(): TemplateContent {
    val PLACEHOLDER = "LLLLLANDINGERRRR00000PAT\\d+"
    var n = 0
    val replacements = LinkedHashMap<String, String>()

    fun process(it: MatchResult): String {
        val id = n++
        val replacement = PLACEHOLDER.replace("\\d+", "$id")
        replacements[replacement] = it.value
        return replacement
    }

    val result = bodyRaw
        .replace(Regex("\\{\\{.*?}}", RegexOption.DOT_MATCHES_ALL)) { process(it) }
        .replace(Regex("\\{%.*?%}", RegexOption.DOT_MATCHES_ALL)) { process(it) }

    val temp = when {
        isMarkDown -> result.kramdownToHtml()
        isHtml -> result
        else -> result
    }

    val finalResult = temp.replace(Regex(PLACEHOLDER)) { replacements[it.value] ?: "" }

    return TemplateContent(this.createFullTextWithBody(finalResult))
}
*/

class LandingServing(val folders: Folders, val entries: Entries) {
    @Transient
    var config: Map<String, Any?> = mapOf()

    @Transient
    var siteData: Map<String, Any?> = mapOf()

    fun reloadConfig() {
        config = yaml.load<Map<String, Any?>>((folders.configYml.takeIfExists()?.readText() ?: "").reader())
        this.siteData = linkedHashMapOf<String, Any?>().also { siteData ->
            for (file in folders.data.walkTopDown()) {
                if (file.extension == "yml") {
                    siteData[file.nameWithoutExtension] = yaml.load<Any?>(file.readText().reader())
                }
            }
        }
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
            },
            Filter("excerpt") {
                subject.toString().substr(0, 200)
            },
            Filter("date_format") {
                val subject = this.subject
                //when (subject) {
                //    is Date -> subject
                //}
                subject.toString()
            }
        ),
        extraFunctions = listOf(
            TeFunction("error") {
                throw NotFoundException()
            },
            TeFunction("now") {
                Date()
            },
            TeFunction("last_update") {
                entries.entries.entries.map { it.date }.max() ?: Date()
            },
            TeFunction("last_post_update") {
                entries.entries.entriesByCategory["posts"]?.map { it.date }?.max() ?: Date()
            }
        ),
        contentTypeProcessor = { content, contentType ->
            when (contentType) {
                "markdown", "kramdown" -> content.kramdownToHtml()
                else -> content
            }
        }
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

    fun buildSiteObject(): Map<String, Any?> {
        return mapOf(
            "config" to config,
            "data" to siteData,
            "collections" to entries.entries.entriesByCategory,
            "posts" to (entries.entries.entriesByCategory["posts"] ?: listOf()),
            "pages" to (entries.entries.entriesByCategory["pages"] ?: listOf())
        )
    }

    suspend fun serveEntry(
        permalink: String, call: ApplicationCall,
        exception: Throwable? = null,
        code: HttpStatusCode = HttpStatusCode.OK
    ) {
        val entry = entries.entries[permalink]
        val params = LinkedHashMap<String, Any?>()
        if (entry != null) {
            val paramsResults = entry.permalinkPattern.matchEntire(permalink)
            if (paramsResults != null) {
                for (name in entry.permalinkNames) {
                    params[name] = paramsResults.groups[name]?.value
                }
            }
        }

        val text = templates.render(permalink, config + mapOf(
            "_request" to mapOf("host" to call.request.host()),
            "_call" to call,
            "site" to buildSiteObject(),
            "params" to params,
            "exception" to exception
        ))
        call.respondText(text, when {
            entry?.isXml == true -> ContentType.Text.Xml
            else -> ContentType.Text.Html
        }, code)
    }

    suspend fun servePost(pipeline: PipelineContext<Unit, ApplicationCall>, permalink: String) = pipeline.apply {
        val permalink = permalink.canonicalPermalink()
        val entry = templateProvider.newGet(permalink)
        if (entry != null) {
            serveEntry(permalink, call)
        } else {
            //println("STATIC: $permalink [0]")
            val file = folders.static.child(permalink)
            //println("STATIC: $permalink -> $file : ${file?.exists()} : ${file?.isFile}")
            if (file?.isFile == true) {
                call.respondFile(file)
            } else {
                throw NotFoundException()
            }
        }
    }
}

