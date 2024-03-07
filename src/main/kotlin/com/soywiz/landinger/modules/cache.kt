package com.soywiz.landinger.modules

import korlibs.time.DateTime
import korlibs.time.TimeSpan
import korlibs.time.days
import com.soywiz.kminiorm.*
import com.soywiz.kminiorm.dialect.SqliteDialect
import korlibs.inject.Singleton
import com.soywiz.landinger.Folders
import java.io.File
import kotlin.reflect.KClass

@Singleton
class Cache(val folders: Folders) {
    suspend inline fun <reified T : Any> get(key: String, ttl: TimeSpan = 365.days, noinline gen: suspend () -> T): T = get(key, T::class, ttl, gen)
    suspend inline fun <reified T : Any> getOrNull(key: String): T? = getOrNull(key, T::class)

    data class Entry(
        @DbPrimary val key: String,
        val content: String,
        val validUntil: Long
    ) : DbBaseModel

    private val sqliteFile = File(folders.cache, "cache.sq3")
    private val dbString = "jdbc:sqlite:${sqliteFile.absoluteFile.toURI()}"
    private val db = try {
        JdbcDb(
            dbString,
            debugSQL = System.getenv("DEBUG_SQL") == "true",
            dialect = SqliteDialect,
            async = true
        )
    } catch (e: Throwable) {
        //System.err.println("Error opening")
        throw RuntimeException("Error opening DB dbString='$dbString'", e)
    }
    private val entries = try {
        db.tableBlocking<Entry>()
    } catch (e: Throwable) {
        //System.err.println("Error opening")
        throw RuntimeException("Error opening DB dbString='$dbString'", e)
    }

    suspend fun has(key: String): Boolean = entries.findOne { it::key eq key AND (it::validUntil ge System.currentTimeMillis()) } != null

    suspend fun put(key: String, value: Any?, ttl: TimeSpan = 365.days) {
        entries.insert(Entry(key, jsonMapper.writeValueAsString(value), (DateTime.now() + ttl).unixMillisLong), onConflict = DbOnConflict.REPLACE)
    }

    suspend fun <T : Any> getOrNull(key: String, clazz: KClass<T>): T? =
        entries.findOne { it::key eq key }?.let { jsonMapper.readValue(it.content, clazz.java) }

    suspend fun <T : Any> get(key: String, clazz: KClass<T>, ttl: TimeSpan = 365.days, gen: suspend () -> T): T {
        if (!has(key)) put(key, gen(), ttl)
        return getOrNull(key, clazz)!!
    }

    /*
    fun getFile(key: String): File = folders.cache[File("$key.cache").name]

    fun has(key: String): Boolean {
        return getFile(key).exists()
    }

    suspend fun put(key: String, value: Any?) {
        return withContext(Dispatchers.IO) {
            val file = getFile(key)
            file.writeText(jsonMapper.writeValueAsString(value))
        }
    }

    suspend fun <T : Any> getOrNull(key: String, clazz: KClass<T>): T? {
        val file = getFile(key)
        return if (file.exists()) jsonMapper.readValue(file.readText(), clazz.java) else null
    }

    suspend fun <T : Any> get(key: String, clazz: KClass<T>, gen: suspend () -> T): T {
        return withContext(Dispatchers.IO) {
            val file = getFile(key)
            if (!file.exists()) {
                put(key, gen())
            }
            jsonMapper.readValue(file.readText(), clazz.java)
        }
    }
     */
}