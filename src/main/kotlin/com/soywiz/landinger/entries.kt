package com.soywiz.landinger

import com.soywiz.klock.*
import korlibs.inject.*
import korlibs.io.dynamic.*
import korlibs.template.dynamic.*
import com.soywiz.landinger.util.*
import io.ktor.http.*
import io.ktor.server.application.*
import java.io.*

class IndexService(val folders: Folders) {
    fun index(folder: File, category: String): EntriesStore = index(listOf(folder), category)
    fun index(folder: List<File>, category: String): EntriesStore {
        val entries = arrayListOf<Entry>()
        val postRegex = Regex("^(\\d+)-(\\d+)-(\\d+)-(.*)$")
        val time = measureTime {
            for (file in folder.flatMap { it.walk() }) {
                if (file.extension == "md" || file.extension == "markdown" || file.extension == "html" || file.extension == "xml") {
                    val mfile = FileWithFrontMatter(file)
                    val header = mfile.header
                    val tags = (header["tags"] as? Iterable<String>?)?.toSet() ?: setOf()
                    var date = DateTime(2020, 1, 1)
                    var permalink: String = file.nameWithoutExtension
                    val title = header["title"]?.toString()?.takeIf { it.isNotBlank() } ?: permalink
                    val dateParts = postRegex.find(file.nameWithoutExtension)
                    if (dateParts != null) {
                        val year = dateParts.groupValues[1].toInt()
                        val month = dateParts.groupValues[2].toInt()
                        val day = dateParts.groupValues[3].toInt()
                        permalink = dateParts.groupValues[4]
                        date = DateTime(year, month, day)
                    }
                    //println("HEADER: $header")
                    if ("date" in header) {
                        val dateStr = header["date"].toString()
                        try {
                            date = (listOf(DateFormat.DEFAULT_FORMAT, DateFormat.FORMAT1, DateFormat("EEE MMM dd HH:mm:ss z yyyy")) + ISO8601.DATE_ALL).asSequence().map { it.tryParse(dateStr)?.utc }.filterNotNull().firstOrNull()
                                ?: error("Can't parse date=$dateStr")
                        } catch (e: Throwable) {
                        }
                    }
                    //println("$permalink - $date")
                    //println("Header: $header, content: $content")
                    //println("Header: $header")
                    entries += Entry(
                        mfile = mfile,
                        date = date,
                        permalink = (header["permalink"]?.toString() ?: permalink).canonicalPermalink(),
                        feature_image = header["feature_image"]?.toString(),
                        icon = header["icon"]?.toString(),
                        title = title,
                        tags = tags,
                        hidden = header["hidden"]?.toString() == "true",
                        category = category,
                        pagination_list = header["pagination_list"]?.toString(),
                        pagination_size = header["pagination_size"]?.toString()?.toIntOrNull(),
                        headers = header
                    )
                }
            }
        }
        return EntriesStore(entries)
    }
}

data class EntriesStore(val allEntries: List<Entry>) {
    val unhiddenEntries by lazy { allEntries.filterNot { it.hidden } }
    val entries by lazy { unhiddenEntries.sortedByDescending { it.date } }
    val entriesByDate by lazy { entries }
    val entriesByPermalink by lazy { entries.associateBy { it.permalink } }
    val entriesSocialCoding by lazy { entriesByDate.filter { "social-coding" in it.tags } }
    val entriesArticles by lazy { entriesByDate.filter { "article" in it.tags } }
    val entriesReleases by lazy { entriesByDate.filter { "release" in it.tags } }
    val entriesLifeLessons by lazy { entriesByDate.filter { "life-lessons" in it.tags } }
    val entriesByCategory by lazy { entries.groupBy { it.category } }
    val dynamicEntries by lazy { entries.filter { it.permalink.contains("{") } }

    operator fun plus(other: EntriesStore) =
        EntriesStore(allEntries + other.allEntries)

    operator fun get(permalink: String): Entry? {
        val cpermalink = permalink.canonicalPermalink()
        val result = entriesByPermalink[cpermalink]
        if (result != null) return result

        for (de in dynamicEntries) {
            if (de.permalinkPattern.matches(cpermalink)) {
                return de
            }
        }

        return null
    }
}

val RequestConnectionPoint.schemePlusHost: String get() = run {
    val defaultPort = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }
    return buildString {
        append(scheme)
        append("://")
        append(host)
        if (port != defaultPort) {
            append(":")
            append(port)
        }
    }
}

data class FileWithFrontMatter(val file: File) {
    val rawFileContent by lazy { file.readText() }
    val isMarkDown = file.name.endsWith(".md") || file.name.endsWith(".markdown")
    val isHtml = file.name.endsWith(".html")
    val isXml = file.name.endsWith(".xml")
    private val parts by lazy {
        val parts = (rawFileContent + "\n").split(Regex("---(\\r\\n|\\r|\\n)"), limit = 3)
        when {
            parts.size >= 3 -> listOf(parts[1], parts[2])
            else -> listOf(null, parts[0])
        }
    }
    val headerRaw by lazy { parts[0] }
    val bodyRaw by lazy { parts[1] ?: "" }
    val bodyHtml by lazy { bodyRaw.toHtml() }
    val fileContentHtml: String by lazy { createFullTextWithBody(bodyHtml)}
    val header: Map<String, Any?> by lazy { if (headerRaw != null) yaml.load<Map<String, Any?>>(headerRaw) else mapOf<String, Any?>() }

    private fun String.toHtml() = if (isMarkDown) kramdownToHtml() else this

    val bodyRawFree by lazy { bodyRaw.forSponsor(false) }
    val bodyRawSponsor by lazy { bodyRaw.forSponsor(true) }

    fun createFullTextWithBody(body: String): String {
        val headerRaw = headerRaw
        return when {
            headerRaw != null -> "---\n${headerRaw.trim()}\n---\n${body}"
            else -> body
        }
    }
}

data class Entry(
    val mfile: FileWithFrontMatter,
    val date: DateTime,
    val permalink: String,
    val feature_image: String?,
    val icon: String?,
    val title: String,
    val tags: Set<String>,
    val hidden: Boolean,
    val category: String,
    val pagination_list: String?,
    val pagination_size: Int?,
    val headers: Map<String, Any?>
) : KorteDynamic2Gettable {
    init {
        check(permalink == permalink.canonicalPermalink())
    }

    val sponsored by lazy {
        mfile.header["sponsor_tier"]?.dyn?.toIntOrNull()?.compareTo(0) == 1
            || rawFileContent.contains("\$SPONSOR\$:")
            //|| rawFileContent.contains("{% if sponsored(")
    }

    override suspend fun dynamic2Get(key: Any?): Any? {
        val keyStr = key.toString()
        return when (keyStr) {
            "title" -> title
            "date" -> date
            "icon" -> icon
            "permalink" -> permalink
            "url" -> permalink
            "body" -> bodyHtml
            "bodyRawFree" -> mfile.bodyRawFree
            "bodyRawSponsor" -> mfile.bodyRawSponsor
            "sponsored" -> sponsored
            else -> headers[keyStr]
        }
    }

    val file = mfile.file
    fun url(call: ApplicationCall): String = this.permalinkUri.absoluteUrl(call)

    val isDynamic = permalink.contains("{")
    val permalinkNames = arrayListOf<String>()
    val permalinkPattern = Regex("^/*" + permalink
        .replace(Regex("\\{(\\w+)}")) {
            val name = it.groupValues[1]
            permalinkNames += name
            if (name == "n") {
                "(?<$name>\\d+)"
            } else {
                "(?<$name>\\w+)"
            }
        } + "/*\$")

    val isMarkDown = mfile.isMarkDown
    val isHtml = mfile.isHtml
    val isXml = mfile.isXml

    val permalinkUri = "/$permalink/"
    val bodyHtml get() = mfile.bodyHtml

    val rawFileContent get() = mfile.rawFileContent
    val htmlWithHeader get() = mfile.fileContentHtml
}


fun String.forSponsor(sponsor: Boolean): String {
    fun update(it: MatchResult): String {
        val kind = it.groupValues[1]
        val content = it.groupValues[2]
        val kindSponsor = kind == "SPONSOR"
        return if (sponsor == kindSponsor) content else ""
    }

    return this
        .replace(Regex("\\$((?:NO)?SPONSOR)\\$:(.*?):\\$\\$", RegexOption.DOT_MATCHES_ALL)) { update(it) }
        //.replace(Regex("\\$((?:NO)?SPONSOR)\\$:(.*)", RegexOption.DOT_MATCHES_ALL)) { update(it) }
}


@Singleton
class Entries(val folders: Folders, val indexService: IndexService) {
    private val _entriesLock = Any()
    private var _entries: EntriesStore? = null
    val entries: EntriesStore
        get() = synchronized(_entriesLock) {
            if (_entries == null) {
                val collectionList = folders.collections.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }?.toList() ?: listOf()
                val time = measureTime {
                    val posts = indexService.index(folders.posts, "posts")
                    val pages = indexService.index(folders.pages, "pages")
                    var entries = posts + pages
                    for (collection in collectionList) {
                        entries += indexService.index(collection, collection.name)
                    }
                    _entries = entries
                }
                println("Loaded ${entries.entries.size} pages in $time. Collections: [${collectionList.joinToString(", ") { it.name }}]")
            }
            _entries!!
        }

    fun entriesReload() {
        _entries = null
    }

}