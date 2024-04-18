package com.soywiz.landinger

import korlibs.image.format.*
import korlibs.inject.*
import korlibs.io.file.std.*
import korlibs.io.lang.*
import korlibs.template.*
import korlibs.template.dynamic.*
import korlibs.crypto.*
import com.soywiz.landinger.korim.*
import com.soywiz.landinger.modules.*
import com.soywiz.landinger.modules.Dynamic.list
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
import korlibs.math.*
import kotlinx.coroutines.*
import java.io.*
import java.text.*
import java.util.Date
import kotlin.collections.*
import kotlin.time.*

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
    var generate = System.getenv("LANDINGER_GENERATE") == "true"
    var showHelp = false

    RegisteredImageFormats.register(PNG, JPEG)

    cli.registerSwitch<String>("-c", "--content-dir", desc = "Sets the content directory (default)") { config.contentDir = it }
    cli.registerSwitch<Boolean>("-s", "--serve", desc = "") { serve = it }
    cli.registerSwitch<String>("-h", "--host", desc = "Sets the host for generating the website") { config.host = it }
    cli.registerSwitch<Int>("-p", "--port", desc = "Sets the port for listening") { config.port = it }
    cli.registerSwitch<Unit>("-g", "--generate", desc = "") { generate = true }
    cli.registerSwitch<Unit>("-h", "--help", desc = "") { showHelp = true }
    cli.registerSwitch<Unit>("-d", "--debug", desc = "Enable debug mode") { config.debug = true }
    cli.registerDefault(desc = "") { error("Unexpected '$it'") }

    cli.parse(params)

    when {
        showHelp -> cli.showHelp()
        generate -> generate(config)
        else -> serve(config)
    }
}

suspend fun generate(config: Config) {
    val timeTotal = measureTime {
        val siteRootFile = File(config.contentDir, "_site").absoluteFile.canonicalFile.also { it.mkdirs() }
        println("Generating content to $siteRootFile")
        val siteRoot = siteRootFile.toVfs().jail()
        val injector = createInjector(config)
        val landing = injector.get<LandingServing>()
        landing.enableReloading = false
        val timeGeneratedPages = measureTime {
            for (entry in landing.entries.entries.allEntries) {
                try {
                    //println("${entry}")

                    suspend fun genPage(entry: Entry, permalink: String = entry.permalink) {
                        val result = landing.generateEntry(permalink)

                        val ext = when (val subtype = result.contentType.contentSubtype) {
                            else -> subtype
                        }

                        println("- $permalink")

                        val path = when {
                            permalink.endsWith(".html") || permalink.endsWith(".xml") -> siteRoot[permalink]
                            else -> siteRoot["$permalink/index.$ext"]
                        }
                        path.parent.mkdirs()
                        path.ensureParents().writeString(result.finalText)
                    }

                    val tplParams = landing.generateTplParams(entry.permalink)

                    if (entry.permalink.contains("{n}") && !entry.pagination_list.isNullOrBlank() && entry.pagination_size != null) {
                        val res = KorteExprNode.parse(entry.pagination_list).eval(KorteTemplate.EvalContext(KorteTemplate.TemplateEvalContext(KorteTemplate("", landing.templateConfig)), tplParams.tplScope, landing.templateConfig, KorteMapper2) { println(it) })
                        val list = res.list
                        val npages = list.size divCeil entry.pagination_size
                        //println("    --> $npages")
                        for (n in 1..npages) {
                            genPage(entry, entry.permalink.replace("{n}", "$n"))
                        }
                    } else {
                        genPage(entry)
                    }


                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }
        println("Time generating pages $timeGeneratedPages")
        val timeCopyStatic = measureTime {
            landing.folders.static.copyRecursively(siteRootFile, overwrite = true)
        }
        println("Time copying static files $timeCopyStatic")
        val timeCopyResizes = measureTime {
            File(landing.folders.cache, "__resizes").copyRecursively(File(siteRootFile, "__resizes"), overwrite = true)
        }
        println("Time copying resized files $timeCopyResizes")
    }
    println("Total time... $timeTotal")
}

fun createInjector(config: Config): Injector = Injector().jvmAutomapping().apply {
    mapInstance(config)
    mapInstance(Folders(File(config.contentDir)))
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

            val injector = createInjector(config)
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
