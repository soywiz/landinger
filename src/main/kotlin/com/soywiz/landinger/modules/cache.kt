package com.soywiz.landinger.modules

import com.soywiz.landinger.*
import com.soywiz.landinger.util.*
import io.ktor.util.*
import korlibs.crypto.*
import korlibs.inject.*
import korlibs.io.lang.*
import korlibs.time.*
import kotlinx.coroutines.*
import java.io.*
import java.io.Closeable
import kotlin.reflect.*
import kotlin.time.*

@Singleton
class Cache(val folders: Folders) : Closeable {
    suspend inline fun <reified T : Any> get(key: String, ttl: Duration = 365.days, gen: () -> T): T = get(key, T::class, ttl, gen)
    suspend inline fun <reified T : Any> getOrNull(key: String): T? = getOrNull(key, T::class)

    private val entriesDir = File(folders.cache, "__entries")

    //private val db = DBMaker
    //    .fileDB(File(folders.cache, "cache.db"))
    //    //.checksumHeaderBypass()
    //    .transactionEnable()
    //    .make()
    //private var cache = db
    //    .hashMap("cache")
    //    .create() as HTreeMap<String, String>

    private fun keyFile(key: String): File {
        val hash = SHA256.digest(key.decodeBase64Bytes()).hexLower
        return File(entriesDir, "${hash.substr(0, 2)}/$hash.bin")
    }

    private suspend fun putRaw(key: String, value: String) {
        withContext(Dispatchers.IO) {
            keyFile(key).also { it.parentFile.mkdirs() }.writeText(value)
        }
    }

    private suspend fun getRaw(key: String): String? {
        return withContext(Dispatchers.IO) {
            keyFile(key).takeIfExists()?.readText()
        }
    }

    suspend fun has(key: String): Boolean = getRaw(key) != null

    suspend fun put(key: String, value: Any?, ttl: Duration = 365.days) {
        putRaw(key, jsonMapper.writeValueAsString(value))
    }

    suspend fun <T : Any> getOrNull(key: String, clazz: KClass<T>): T? =
        getRaw(key)?.let { jsonMapper.readValue(it, clazz.java) }
        //entries.findOne { it::key eq key }?.let { jsonMapper.readValue(it.content, clazz.java) }

    suspend inline fun <T : Any> get(key: String, clazz: KClass<T>, ttl: Duration = 365.days, gen: () -> T): T {
        if (!has(key)) put(key, gen(), ttl)
        return getOrNull(key, clazz)!!
    }

    override fun close() {
        //db.close()
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