package com.soywiz.landinger

import com.soywiz.klock.*
import com.soywiz.korio.file.std.*
import com.soywiz.landinger.util.absoluteUrl
import com.soywiz.landinger.util.kramdownToHtml
import com.soywiz.landinger.util.yaml
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import java.io.*

class IndexService(val folders: Folders) {
    fun index(folder: File): EntriesStore {
        val entries = arrayListOf<Entry>()
        val postRegex = Regex("^(\\d+)-(\\d+)-(\\d+)-(.*)$")
        val time = measureTime {
            for (file in folder.walk()) {
                if (file.extension == "md" || file.extension == "markdown" || file.extension == "html") {
                    val mfile = FileWithFrontMatter(file)
                    val header = mfile.header
                    val tags = (header["tags"] as? Iterable<String>?)?.toSet() ?: setOf()
                    var date = DateTime(2020, 1, 1)
                    var permalink: String = file.nameWithoutExtension
                    val title = header["title"]?.toString() ?: permalink
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
                        permalink = (header["permalink"]?.toString() ?: permalink).trimStart('/'),
                        feature_image = header["feature_image"]?.toString(),
                        icon = header["icon"]?.toString(),
                        title = title,
                        tags = tags,
                        hidden = header["hidden"]?.toString() == "true"
                    )
                }
            }
        }
        return EntriesStore(entries)
    }
}

data class EntriesStore(val allEntries: List<Entry>) {
    val entries = allEntries.filterNot { it.hidden }.sortedByDescending { it.date }
    val entriesByDate = entries
    val entriesByPermalink = entries.associateBy { it.permalink }
    val entriesSocialCoding = entriesByDate.filter { "social-coding" in it.tags }
    val entriesArticles = entriesByDate.filter { "article" in it.tags }
    val entriesReleases = entriesByDate.filter { "release" in it.tags }
    val entriesLifeLessons = entriesByDate.filter { "life-lessons" in it.tags }

    operator fun plus(other: EntriesStore) =
        EntriesStore(allEntries + other.allEntries)

    operator fun get(permalink: String) = entriesByPermalink[permalink]
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

class FileWithFrontMatter(val file: File) {
    val rawFileContent by lazy { file.readText() }
    val isMarkdown = file.name.endsWith(".md") || file.name.endsWith(".markdown")
    val isHtml = file.name.endsWith(".html")
    private val parts by lazy {
        val parts = rawFileContent.split("---\n", limit = 3)
        when {
            parts.size >= 3 -> listOf(parts[1], parts[2])
            else -> listOf(null, parts[0])
        }
    }
    val headerRaw by lazy { parts[0] }
    val bodyRaw by lazy { parts[1] ?: "" }
    val bodyHtml by lazy { if (isMarkdown) bodyRaw.kramdownToHtml() else bodyRaw }
    val fileContentHtml by lazy {  createFullTextWithBody(bodyHtml)}
    val header: Map<String, Any?> by lazy { if (headerRaw != null) yaml.load<Map<String, Any?>>(headerRaw) else mapOf<String, Any?>() }

    fun createFullTextWithBody(body: String): String {
        val headerRaw = headerRaw
        return when {
            headerRaw != null -> "---\n${headerRaw!!.trim()}\n---\n${body}"
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
    val hidden: Boolean
) {
    val file = mfile.file
    fun url(call: ApplicationCall): String = this.permalinkUri.absoluteUrl(call)

    val isMarkDown = (file.name.endsWith(".md")) || (file.name.endsWith(".markdown"))

    val permalinkUri = "/$permalink/"
    val bodyHtml get() = mfile.bodyHtml

    val htmlWithHeader get() = mfile.fileContentHtml
}

class Entries(val folders: Folders, val indexService: IndexService) {
    private val _entriesLock = Any()
    private var _entries: EntriesStore? = null
    val entries: EntriesStore
        get() = synchronized(_entriesLock) {
            if (_entries == null) {
                val time = measureTime {
                    val entries = indexService.index(folders.posts) + indexService.index(folders.pages)
                    _entries = entries
                }
                println("Loaded ${entries.entries.size} posts in $time")
            }
            _entries!!
        }

    fun entriesReload() {
        _entries = null
    }

}