package com.soywiz.landinger

import com.soywiz.klock.*
import com.soywiz.klock.DateFormat
import com.soywiz.klock.jvm.*
import korlibs.image.bitmap.*
import korlibs.image.format.*
import korlibs.inject.*
import korlibs.io.dynamic.*
import korlibs.io.file.std.*
import korlibs.io.lang.*
import korlibs.io.stream.*
import korlibs.math.geom.*
import korlibs.template.*
import korlibs.template.dynamic.*
import korlibs.template.util.*
import korlibs.crypto.*
import com.soywiz.landinger.korim.*
import com.soywiz.landinger.modules.*
import com.soywiz.landinger.modules.Dynamic.str
import com.soywiz.landinger.util.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.*
import org.jsoup.*
import org.jsoup.safety.*
import java.io.*
import java.io.FileNotFoundException
import java.text.*
import java.util.Date
import kotlin.collections.*

suspend fun main(args: Array<String>) {
    //System.setProperty("java.awt.headless", "false")
    System.setProperty("java.awt.headless", "true")

    //luceneIndex.search("hello")
    val params1 = System.getProperty("landinger.args")?.toString()?.let { CliParser.parseString(it) }
    val params2 = args.toList()
    val params = params1 ?: params2
    val cli = CliParser()

    var config = Config()
    var serve = false
    var generate = false
    var showHelp = false

    RegisteredImageFormats.register(PNG, JPEG)

    cli.registerSwitch<String>("-c", "--content-dir", desc = "Sets the content directory (default)") { config.contentDir = it }
    cli.registerSwitch<Boolean>("-s", "--serve", desc = "") { serve = it }
    cli.registerSwitch<String>("-h", "--host", desc = "Sets the host for generating the website") { config.host = it }
    cli.registerSwitch<Int>("-p", "--port", desc = "Sets the port for listening") { config.port = it }
    cli.registerSwitch<Boolean>("-g", "--generate", desc = "") { generate = it }
    cli.registerSwitch<Unit>("-h", "--help", desc = "") { showHelp = true }
    cli.registerSwitch<Unit>("-d", "--debug", desc = "Enable debug mode") { config.debug = true }
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
            install(XForwardedHeaders)
            install(PartialContent) {
                maxRangeCount = 10
            }
            install(CachingHeaders) {
                options { call, outgoingContent ->
                    val contentType = outgoingContent.contentType?.withoutParameters() ?: ContentType.Any
                    when {
                        contentType.match(ContentType.Text.CSS) -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 2 * 60 * 60))
                        contentType.match(ContentType.Image.Any) -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 365 * 24 * 60 * 60))
                        else -> null
                    }
                }
            }

            val injector = AsyncInjector().jvmAutomapping()
            injector.mapInstance(config)
            injector.mapInstance(Folders(File(config.contentDir)))
            val landing = injector.get<LandingServing>()

            installLogin(injector)
            installDeploy(injector)
            install(StatusPages) {
                exception<NotFoundException> { call, cause ->
                    try {
                        landing.serveEntry("/404", call, cause, code = HttpStatusCode.NotFound)
                    } catch (e: Throwable) {
                        call.respondText("Not Found", ContentType.Text.Html, HttpStatusCode.NotFound)
                    }
                }
                exception<HttpRedirectException> { call, cause ->
                    call.respondRedirect(cause.url, cause.permanent)
                }
                exception<Throwable> { call, cause ->
                    cause.printStackTrace()
                    System.err.println(cause.toString())
                    call.respondText("Internal Server Error", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                }
            }
            routing {
                route("/") {
                    get("/") {
                        landing.servePost(this, "")
                    }
                    get("/{permalink...}") {
                        landing.servePost(this, call.request.uri)
                    }
                    get("/{permalink...}/") {
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
    val layouts = listOf(content["layouts"], content["_layouts"])
    val includes = listOf(content["includes"], content["_includes"])
    val pages = listOf(content["pages"], content["_pages"])
    val data = content["data"]
    val posts = listOf(content["posts"], content["_posts"])
    val collections = content["collections"]
    val static = content["static"]
    val cache = content[".cache"].also { it.mkdirs() }
    val configYml = listOf(content["config.yml"], content["_config.yml"])
    val secretsYml = content["secrets.yml"]
}

fun List<File>.takeIfExists(): File? {
    return this.firstNotNullOfOrNull { it.takeIfExists() }
}

fun FileWithFrontMatter.toTemplateContent(): KorteTemplateContent {
    //println("rawFileContent: $rawFileContent")
    return KorteTemplateContent(
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
    val youtube: YoutubeService,
    val cache: Cache
) {
    val templateProvider = object : KorteNewTemplateProvider {
        override suspend fun newGet(template: String): KorteTemplateContent? {
            val entry = entries.entries[template] ?: return null
            return entry.mfile.toTemplateContent()
        }
    }

    class TemplateProviderWithFrontMatter(val paths: List<File>) : KorteNewTemplateProvider {
        constructor(path: File) : this(listOf(path))

        override suspend fun newGet(template: String): KorteTemplateContent? {
            //println("INCLUDE: '$template'")
            for (filePath in listOf(template, "$template.md", "$template.html")) {
                for (path in paths) {
                    val file = path.child(filePath)
                    if (file != null && file.exists() && !file.isDirectory) {
                        return FileWithFrontMatter(file).toTemplateContent()
                    }
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

    fun getAbsoluteUrl(url: String, scope: KorteTemplate.Scope): String {
        return runBlocking { getAbsoluteUrl(url, scope.get("_call") as ApplicationCall) }
    }

    private val templateConfig2: KorteTemplateConfig get() = templateConfig

    fun getAbsoluteFile(path: String): File? {
        return folders.static.child(path)?.takeIfExists()?.canonicalFile
    }

    val Include = KorteTag("include", setOf(), null) {
        val main = chunks.first()
        val content = main.tag.content
        val hasExtension = content.contains(".html") || content.contains(".md") || content.contains(".markdown")

        val expr: KorteExprNode
        val tr: KorteListReader<KorteExprNode.Token>
        if (hasExtension) {
            val (fileName, extraTags) = main.tag.content.trim().split(Regex("\\s+"), limit = 2) + listOf("")
            tr = KorteExprNode.Token.tokenize(extraTags, main.tag.posContext)
            expr = KorteExprNode.LIT(fileName)
        } else {
            tr = main.tag.tokens
            expr = KorteExprNode.parseExpr(tr)
        }

        val params = linkedMapOf<String, KorteExprNode>()
        while (tr.hasMore) {
            val id = KorteExprNode.parseId(tr)
            tr.expect("=")
            val expr = KorteExprNode.parseExpr(tr)
            params[id] = expr
        }
        tr.expectEnd()
        DefaultBlocks.BlockInclude(expr, params, main.tag.posContext, content)
    }

    val templateConfig = KorteTemplateConfig(
        extraTags = listOf(
            KorteTag("import_css", setOf(), null) {
                //val expr = chunks[0].tag.expr
                val expr = chunks[0].tag.content.trimStart('"').trimEnd('"')
                DefaultBlocks.BlockText(folders.static.child(expr)!!.readText().compressCss())
            },
            KorteTag("seo", setOf(), null) {
                DefaultBlocks.BlockText("<!-- seo -->")
            },
            Include
        ),
        extraFilters = listOf(
            KorteFilter("sha1") {
                subject.str.sha1().hexLower
            },
            KorteFilter("default") {
                when (subject) {
                    null, false, "" -> this.args[0]
                    else -> subject
                }
            },
            KorteFilter("img_src") {
                val path = subject.toString()
                val absPath = getAbsoluteUrl(path, context.scope)
                absPath
            },
            KorteFilter("strip_html") {
                if (subject == null) "" else Jsoup.parse(subject.toString()).text()
            },
            KorteFilter("truncatewords") {
                val count = args.getOrNull(0).dyn.toIntOrNull() ?: 10
                val ellipsis = args.getOrNull(1).dyn.toStringOrNull() ?: "..."
                subject.toString().splitKeep(Regex("\\W+")).take(count).joinToString("") + ellipsis
                //subject.toString()
            },
            KorteFilter("slugify") {
                this.subject.toString().replace("\\W+", "-")
            },
            KorteFilter("img_srcset") {
                val path = subject.toString()
                val absPath = getAbsoluteUrl(path, context.scope)
                args.map { it.toDynamicInt() }.joinToString(", ") { "$absPath ${it}w" }
            },
            KorteFilter("absolute") { getAbsoluteUrl(subject.toString(), context.scope.get("_call") as ApplicationCall) },
            KorteFilter("absolute_url") {
                getAbsoluteUrl(
                    subject.toString(),
                    context.scope.get("_call") as ApplicationCall
                )
            },
            KorteFilter("excerpt") {
                Jsoup.clean(subject.toString().substr(0, 200), Safelist.relaxed())
            },
            KorteFilter("eval_template") {
                //fun Template.Scope.root(): Template.Scope = this?.parent?.root() ?: this
                val subject: Any = this.subject ?: ""
                val str = when (subject) {
                    is KorteRawString -> subject.str
                    else -> subject.toString()
                }

                try {
                    val template = Template(str, this.context.templates)
                    template
                        .createEvalContext()
                        .exec(this.context.scope.map, mapper = mapper, parentScope = this.context.scope)
                        .str
                } catch (e: Throwable) {

                    e.printStackTrace()
                    //System.err.println(str)
                    //"--ERROR-- : ${subject::class} : $str"
                    "--ERROR--"
                }
            },
            KorteFilter("markdown_to_html") {
                subject.toString().kramdownToHtml()
            },
            KorteFilter("date_format") {
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
            KorteFilter("date") {
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
            KorteFilter("image_size") {
                try {
                    val file = getAbsoluteFile(subject.dyn.str)
                    cache.get("image_size.file.${file?.absolutePath?.toByteArray(UTF8)?.sha1()?.hex}") {
                        val bytes = file?.readBytes()
                        val sha1 = bytes?.sha1()?.hex
                        cache.get("image_size.hash.$sha1") {
                            val header = try {
                                bytes?.let { RegisteredImageFormats.decodeHeader(it.openSync()) }
                            } catch (e: Throwable) {
                                null
                            }
                            mapOf("width" to (header?.width ?: 0), "height" to (header?.height ?: 0))
                        }
                    }
                } catch (e: Throwable) {
                    if (e !is FileNotFoundException) {
                        e.printStackTrace()
                    }
                    mapOf("width" to 0, "height" to 0)
                }
            },
            KorteFilter("resized_image") {
                try {
                    val file = getAbsoluteFile(subject.dyn.str)
                    val width = args.getOrNull(0).dyn.int
                    val height = args.getOrNull(1).dyn.int
                    val mode = args.getOrNull(2)?.toString() ?: "cover"
                    val mmode: ScaleMode = when (mode) {
                        "cover" -> ScaleMode.COVER
                        "fit", "show_all" -> ScaleMode.SHOW_ALL
                        "unscaled", "no_scale" -> ScaleMode.NO_SCALE
                        "fill", "exact" -> ScaleMode.EXACT
                        else -> ScaleMode.COVER
                    }
                    if (file == null) {
                        subject
                    } else {
                        val nameSha1 = file.canonicalPath.sha1().hex

                        val baseFileName = "${width}x${height}/${nameSha1.substr(0, 1)}/${nameSha1.substr(0, 2)}/${nameSha1.substr(0, 4)}/${nameSha1}.jpg"
                        val resizesFile = File(folders.cache, "__resizes/$baseFileName")
                        if (!resizesFile.exists()) {
                            resizesFile.parentFile.mkdirs()
                            file.toVfs().readBitmap().toBMP32()
                                .resized(width, height, mmode, Anchor.MIDDLE_CENTER)
                                .writeTo(resizesFile.toVfs(), JPEG)
                        }
                        "/__resizes/$baseFileName"
                    }
                } catch (e: Throwable) {
                    if (e !is FileNotFoundException) {
                        e.printStackTrace()
                    }
                    "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
                }
                //""
            },
            KorteFilter("date_rfc3339") {
                val subject = this.subject
                val date: DateTime = when (subject) {
                    is Date -> subject.toDateTime()
                    is DateTime -> subject
                    else -> parseAnyDate(subject.toString())?.toDateTime() ?: DateTime.EPOCH
                }
                DateFormat.FORMAT1.format(date)
            },
            KorteFilter("date_to_xmlschema") {
                val subject = this.subject
                val date: DateTime = when (subject) {
                    is Date -> subject.toDateTime()
                    is DateTime -> subject
                    else -> parseAnyDate(subject.toString())?.toDateTime() ?: DateTime.EPOCH
                }
                DateFormat.FORMAT1.format(date)
            },
            KorteFilter("date_to_string") {
                val subject = this.subject
                val date: DateTime = when (subject) {
                    is Date -> subject.toDateTime()
                    is DateTime -> subject
                    else -> kotlin.runCatching { DateTime.parse(subject.toString()).utc }.getOrNull() ?: DateTime.EPOCH
                }
                try {
                    if (this.args.isNotEmpty()) {
                        date.format(this.args[0].str)
                    } else {
                        date.toString(DateFormat("dd MMM YYYY"))
                    }
                } catch (e: Throwable) {
                    date.toString(DateFormat.DEFAULT_FORMAT)
                }
            },
            KorteFilter("where_exp") {
                val ctx = this.context
                val list = this.subject.toDynamicList()
                val args = this.args.toDynamicList()
                val itemName = if (args.size >= 2) args[0].toDynamicString() else "it"
                val itemExprStr = args.last().toDynamicString()
                val itemExpr = KorteExprNode.parse(itemExprStr, KorteFilePosContext(KorteFileContext("", itemExprStr), 0))

                ctx.createScope {
                    list.filter {
                        ctx.scope.set(itemName, it)
                        itemExpr.eval(ctx).toDynamicBool()
                    }
                }
            },
            KorteFilter("xml_escape") {
                this.subject.toString()
            },
            KorteFilter("remove") {
                this.subject.toString().replace(this.args.firstOrNull()?.toString() ?: "", "")
            }
        ),
        extraFunctions = listOf(
            KorteFunction("sponsored") {
                val price = KorteDynamic2.accessAny(this.scope.get("session"), "price", mapper).dyn.int
                val post_sponsor_tier = KorteDynamic2.accessAny(this.scope.get("post"), "sponsor_tier", mapper).dyn.toIntOrNull()
                val post_sponsor_tier_2 = KorteDynamic2.accessAny(this.scope.get("page"), "sponsor_tier", mapper).dyn.toIntOrNull()
                val sponsor_tier = post_sponsor_tier ?: post_sponsor_tier_2 ?: it.getOrNull(0).dyn.toIntOrNull() ?: 1
                //println("session=${this.scope.get("session").dyn["price"]}")
                //println("post_sponsor_tier=${post_sponsor_tier}")
                //println("sponsorted: price=$price, sponsor_tier=$sponsor_tier")
                price >= sponsor_tier
            },
            KorteFunction("error") { throw NotFoundException() },
            KorteFunction("not_found") { throw NotFoundException() },
            KorteFunction("permanent_redirect") { throw HttpRedirectException(it[0].toString(), permanent = true) },
            KorteFunction("temporal_redirect") { throw HttpRedirectException(it[0].toString(), permanent = false) },
            KorteFunction("now") {
                Date()
            },
            KorteFunction("last_update") {
                entries.entries.entries.map { it.date }.maxOrNull() ?: Date()
            },
            KorteFunction("last_post_update") {
                entries.entries.entriesByCategory["posts"]?.map { it.date }?.maxOrNull() ?: Date()
            },
            KorteFunction("youtube_info") {
                val ids = it[0].dyn.list.map {
                    (if (it.value is Map<*, *>) it["id"].str else it.str).trim()
                }
                val list = youtube.getYoutubeVideoInfo(ids)
                //println("youtube_ids: ${ids.joinToString(" || ")}")
                //println(" --> ")
                if (it[0] is String) list.getOrNull(0) else list
            }
        ),
        contentTypeProcessor = { content, contentType ->
            when (contentType) {
                "markdown", "kramdown" -> content.kramdownToHtml()
                else -> content
            }
        }
    )
    val templates = KorteTemplates(templateProvider, includesProvider, layoutsProvider, templateConfig, cache = true)

    var doReload = LockSignal()

    init {
        Thread {
            while (true) {
                doReload.wait()
                Thread.sleep(50L)
                println("Reloading...")
                val time = measureTime {
                    entries.entriesReload()
                    templates.invalidateCache()
                    configService.reloadConfig()
                }
                println("Reloaded in $time")
            }
        }.apply { isDaemon = true }.start()
        //folders.content.watchTree { baseFile, path ->
        folders.content.watchTreeNew { fullFile ->
            println("Changed: $fullFile")
            if (!fullFile.canonicalPath.contains(".cache") && !fullFile.canonicalPath.contains(".idea")) {
                doReload.notifyAll()
            }
        }
        configService.reloadConfig()
    }

    fun buildSiteObject(): Map<String, Any?> {
        return configService.config + mapOf(
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
                    val value = paramsResults.groups.get(name)?.value
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

        call.respondText(
            finalText, when (entry?.isXml) {
                true -> ContentType.Text.Xml
                else -> ContentType.Text.Html
            }, code
        )
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
            if (permalink.startsWith("/__resizes/")) {
                val file = folders.cache["__resizes"].child(permalink.removePrefix("/__resizes/"))
                //println("STATIC: $permalink -> $file : ${file?.exists()} : ${file?.isFile}")
                if (file?.isFile == true) {
                    call.respondFile(file)
                } else {
                    throw NotFoundException()
                }
            } else {
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
}

// @TODO: Move to KorIM?
private fun Bitmap.resized(width: Int, height: Int, scale: ScaleMode, anchor: Anchor): Bitmap {
    val bmp = this
    val out = bmp.createWithThisFormat(width, height)
    out.context2d(antialiased = true) {
        val rect = Rectangle(0, 0, width, height).place(bmp.size.toDouble(), anchor, scale)
        drawImage(bmp, rect.position, rect.size)
    }
    return out
}

fun String.sha1() = this.toByteArray(UTF8).sha1()

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

suspend fun Template(
    template: String,
    templates: KorteTemplates,
    includes: KorteNewTemplateProvider = templates.includes,
    layouts: KorteNewTemplateProvider = templates.layouts,
    config: KorteTemplateConfig = templates.config,
    cache: Boolean = templates.cache,
): KorteTemplate {
    val root = KorteTemplateProvider(mapOf("template" to template))
    return KorteTemplates(
        root = root,
        includes = includes,
        layouts = layouts,
        config = config,
        cache = cache,
    ).get("template")
}

suspend fun KorteTemplate.TemplateEvalContext.exec(args: Any?, mapper: KorteObjectMapper2 = KorteMapper2, parentScope: KorteTemplate.Scope? = null): KorteTemplate.ExecResult {
    val str = StringBuilder()
    val scope = KorteTemplate.Scope(args, mapper, parentScope)
    if (template.frontMatter != null) for ((k, v) in template.frontMatter!!) scope.set(k, v)
    val context = KorteTemplate.EvalContext(this, scope, template.config, mapper, write = { str.append(it) })
    eval(context)
    return KorteTemplate.ExecResult(context, str.toString())
}
