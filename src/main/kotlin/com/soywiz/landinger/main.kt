package com.soywiz.landinger

import com.soywiz.klock.DateFormat
import com.soywiz.klock.DateTime
import com.soywiz.klock.format
import com.soywiz.klock.jvm.toDate
import com.soywiz.klock.jvm.toDateTime
import com.soywiz.korinject.AsyncInjector
import com.soywiz.korinject.Singleton
import com.soywiz.korinject.jvmAutomapping
import com.soywiz.korio.file.std.get
import com.soywiz.korio.lang.substr
import com.soywiz.korte.*
import com.soywiz.korte.dynamic.Mapper2
import com.soywiz.landinger.modules.*
import com.soywiz.landinger.modules.Dynamic.list
import com.soywiz.landinger.modules.Dynamic.str
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
import io.ktor.response.respondRedirect
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.safety.Whitelist
import java.io.File
import java.text.SimpleDateFormat
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

fun serve(config: Config) {
    embeddedServer(Netty, port = config.port) {
        runBlocking {
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

            val injector = AsyncInjector().jvmAutomapping()
            injector.mapInstance(Folders(File(config.contentDir)))
            val landing = injector.get<LandingServing>()

            installLogin(injector)
            installDeploy(injector)
            routing {
                install(StatusPages) {
                    exception<NotFoundException> { cause ->
                        try {
                            landing.serveEntry("/404", call, cause, code = HttpStatusCode.NotFound)
                        } catch (e: Throwable) {
                            call.respondText("Not Found", ContentType.Text.Html, HttpStatusCode.NotFound)
                        }
                    }
                    exception<HttpRedirectException> { cause ->
                        call.respondRedirect(cause.url, cause.permanent)
                    }
                    exception<Throwable> { cause ->
                        cause.printStackTrace()
                        System.err.println(cause.toString())
                        call.respondText("Internal Server Error", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                    }
                }
                route("/") {
                    get("/") {
                        landing.servePost(this, "")
                    }
                    get("/{permalink...}") {
                        landing.servePost(this, call.request.uri)
                    }
                }
            }
        }
    }.start(wait = true)
}

@Singleton
class Folders(content: File) {
    val content = content.canonicalFile
    val layouts = content["layouts"]
    val includes = content["includes"]
    val pages = content["pages"]
    val data = content["data"]
    val posts = content["posts"]
    val collections = content["collections"]
    val static = content["static"]
    val cache = content[".cache"]
    val configYml = content["config.yml"]
    val secretsYml = content["secrets.yml"]
}

fun FileWithFrontMatter.toTemplateContent(): TemplateContent {
    //println("rawFileContent: $rawFileContent")
    return TemplateContent(
        rawFileContent, when {
            isMarkDown -> "markdown"
            isXml -> "xml"
            else -> null
        }
    )
}

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

class HttpRedirectException(val url: String, val permanent: Boolean) : Throwable()

@Singleton
class LandingServing(
    val folders: Folders,
    val entries: Entries,
    val configService: ConfigService,
    val pageShownBus: PageShownBus,
    val youtube: YoutubeService
) {
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

    private val templateConfig2: TemplateConfig get() = templateConfig

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
            Filter("absolute") { getAbsoluteUrl(subject.toString(), context.scope.get("_call") as ApplicationCall) },
            Filter("absolute_url") { getAbsoluteUrl(subject.toString(), context.scope.get("_call") as ApplicationCall) },
            Filter("excerpt") {
                Jsoup.clean(subject.toString().substr(0, 200), Whitelist.relaxed())
            },
            Filter("eval_template") {
                //fun Template.Scope.root(): Template.Scope = this?.parent?.root() ?: this
                val subject: Any = this.subject ?: ""
                val str = when (subject) {
                    is RawString -> subject.str
                    else -> subject.toString()
                }
                try {
                    Template(str, this.context.config)(this.context.scope.map, this.context.mapper)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    //System.err.println(str)
                    //"--ERROR-- : ${subject::class} : $str"
                    "--ERROR--"
                }
            },
            Filter("markdown_to_html") {
                subject.toString().kramdownToHtml()
            },
            Filter("date_format") {
                val subject = this.subject
                val date = when (subject) {
                    is Date -> subject
                    is DateTime -> subject.toDate()
                    else -> parseAnyDate(subject.toString())
                }
                //when (subject) {
                //    is Date -> subject
                //}
                if (args.isEmpty()) {
                    subject.toString()
                } else {
                    SimpleDateFormat(args[0].toDynamicString()).format(date ?: Date(0L))
                }

            },
            Filter("date_rfc3339") {
                val subject = this.subject
                val date: DateTime = when (subject) {
                    is Date -> subject.toDateTime()
                    is DateTime -> subject
                    else -> parseAnyDate(subject.toString())?.toDateTime() ?: DateTime.EPOCH
                }
                DateFormat.FORMAT1.format(date)
            },
            Filter("date_to_string") {
                val subject = this.subject
                //when (subject) {
                //    is Date -> subject
                //}
                subject.toString()
            },
            Filter("where_exp") {
                val ctx = this.context
                val list = this.subject.toDynamicList()
                val args = this.args.toDynamicList()
                val itemName = if (args.size >= 2) args[0].toDynamicString() else "it"
                val itemExprStr = args.last().toDynamicString()
                val itemExpr = ExprNode.parse(itemExprStr, FilePosContext(FileContext("", itemExprStr), 0))

                ctx.createScope {
                    list.filter {
                        ctx.scope.set(itemName, it)
                        itemExpr.eval(ctx).toDynamicBool()
                    }
                }
            }
        ),
        extraFunctions = listOf(
            TeFunction("error") { throw NotFoundException() },
            TeFunction("not_found") { throw NotFoundException() },
            TeFunction("permanent_redirect") { throw HttpRedirectException(it[0].toString(), permanent = true) },
            TeFunction("temporal_redirect") { throw HttpRedirectException(it[0].toString(), permanent = false) },
            TeFunction("now") {
                Date()
            },
            TeFunction("last_update") {
                entries.entries.entries.map { it.date }.max() ?: Date()
            },
            TeFunction("last_post_update") {
                entries.entries.entriesByCategory["posts"]?.map { it.date }?.max() ?: Date()
            },
            TeFunction("youtube_info") {
                val list = youtube.getYoutubeVideoInfo(it[0].list.map { it.str })
                if (it[0] is String) list[0] else list
            }
            /*
            curl \
                'https://www.googleapis.com/youtube/v3/videos?part=contentDetails&part=snippet&id=mfJtWm5UddM&id=fCE7-ofMVbM&key=[YOUR_API_KEY]' \

              --header 'Accept: application/json' \
              --compressed

             */
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
                configService.reloadConfig()
            }
        }.apply { isDaemon = true }.start()
        folders.content.watchTree {
            doReload.notifyAll()
        }
        configService.reloadConfig()
    }

    fun buildSiteObject(): Map<String, Any?> {
        return mapOf(
            "config" to configService.config,
            "data" to configService.siteData,
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
                    val value = paramsResults.groups[name]?.value
                    params[name] = if (name == "n") value?.toInt() else value
                }
            }
        }

        val page = PageShownBus.Page(call, entry, permalink)
        if (code.value < 400) {
            pageShownBus.pageShown(page)
        }

        val tplParams = configService.config + mapOf(
            "_request" to mapOf("host" to call.request.host()),
            "_call" to call,
            "site" to buildSiteObject(),
            "params" to params,
            "page" to entry,
            "exception" to exception
        ) + configService.extraConfig + page.extraConfig
        //println("configService.extraConfig: ${configService.extraConfig} : $configService")
        val text = templates.render(permalink, tplParams)
        val finalText = text.forSponsor(page.isSponsor)

        call.respondText(finalText, when {
            entry?.isXml == true -> ContentType.Text.Xml
            else -> ContentType.Text.Html
        }, code)
    }

    suspend fun servePost(pipeline: PipelineContext<Unit, ApplicationCall>, permalink: String) = pipeline.apply {
        if (permalink != "" && permalink.endsWith("/")) {
            throw HttpRedirectException(permalink.canonicalPermalink().absoluteUrl(call), permanent = true)
        }
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

private val DATE_FORMAT_REGEXPS: Map<String, String> =
    mapOf(
            "^\\d{8}$" to "yyyyMMdd",
            "^\\d{1,2}-\\d{1,2}-\\d{4}$" to "dd-MM-yyyy",
            "^\\d{4}-\\d{1,2}-\\d{1,2}$" to "yyyy-MM-dd",
            "^\\d{1,2}/\\d{1,2}/\\d{4}$" to "MM/dd/yyyy",
            "^\\d{4}/\\d{1,2}/\\d{1,2}$" to "yyyy/MM/dd",
            "^\\d{1,2}\\s[a-z]{3}\\s\\d{4}$" to "dd MMM yyyy",
            "^\\d{1,2}\\s[a-z]{4,}\\s\\d{4}$" to "dd MMMM yyyy",
            "^\\d{12}$" to "yyyyMMddHHmm",
            "^\\d{8}\\s\\d{4}$" to "yyyyMMdd HHmm",
            "^\\d{1,2}-\\d{1,2}-\\d{4}\\s\\d{1,2}:\\d{2}$" to "dd-MM-yyyy HH:mm",
            "^\\d{4}-\\d{1,2}-\\d{1,2}\\s\\d{1,2}:\\d{2}$" to "yyyy-MM-dd HH:mm",
            "^\\d{1,2}/\\d{1,2}/\\d{4}\\s\\d{1,2}:\\d{2}$" to "MM/dd/yyyy HH:mm",
            "^\\d{4}/\\d{1,2}/\\d{1,2}\\s\\d{1,2}:\\d{2}$" to "yyyy/MM/dd HH:mm",
            "^\\d{1,2}\\s[a-z]{3}\\s\\d{4}\\s\\d{1,2}:\\d{2}$" to "dd MMM yyyy HH:mm",
            "^\\d{1,2}\\s[a-z]{4,}\\s\\d{4}\\s\\d{1,2}:\\d{2}$" to "dd MMMM yyyy HH:mm",
            "^\\d{14}$" to "yyyyMMddHHmmss",
            "^\\d{8}\\s\\d{6}$" to "yyyyMMdd HHmmss",
            "^\\d{1,2}-\\d{1,2}-\\d{4}\\s\\d{1,2}:\\d{2}:\\d{2}$" to "dd-MM-yyyy HH:mm:ss",
            "^\\d{4}-\\d{1,2}-\\d{1,2}\\s\\d{1,2}:\\d{2}:\\d{2}$" to "yyyy-MM-dd HH:mm:ss",
            "^\\d{1,2}/\\d{1,2}/\\d{4}\\s\\d{1,2}:\\d{2}:\\d{2}$" to "MM/dd/yyyy HH:mm:ss",
            "^\\d{4}/\\d{1,2}/\\d{1,2}\\s\\d{1,2}:\\d{2}:\\d{2}$" to "yyyy/MM/dd HH:mm:ss",
            "^\\d{1,2}\\s[a-z]{3}\\s\\d{4}\\s\\d{1,2}:\\d{2}:\\d{2}$" to "dd MMM yyyy HH:mm:ss",
            "^\\d{1,2}\\s[a-z]{4,}\\s\\d{4}\\s\\d{1,2}:\\d{2}:\\d{2}$" to "dd MMMM yyyy HH:mm:ss"
)

/**
 * Determine SimpleDateFormat pattern matching with the given date string. Returns null if
 * format is unknown. You can simply extend DateUtil with more formats if needed.
 * @param dateString The date string to determine the SimpleDateFormat pattern for.
 * @return The matching SimpleDateFormat pattern, or null if format is unknown.
 * @see SimpleDateFormat
 */
fun determineDateFormat(dateString: String): String? {
    for (regexp in DATE_FORMAT_REGEXPS.keys) {
        if (dateString.toLowerCase().matches(Regex(regexp))) {
            return DATE_FORMAT_REGEXPS[regexp]
        }
    }
    return null // Unknown format.
}

fun parseAnyDate(dateString: String): Date? {
    val format = determineDateFormat(dateString) ?: return null
    return SimpleDateFormat(format).parse(dateString)
}