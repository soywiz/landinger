package com.soywiz.landinger.modules

import com.soywiz.korinject.Singleton
import com.soywiz.korio.file.std.get
import com.soywiz.landinger.Folders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.reflect.KClass

@Singleton
class Cache(val folders: Folders) {
    suspend inline fun <reified T : Any> get(key: String, noinline gen: suspend () -> T): T = get(key, T::class, gen)
    suspend inline fun <reified T : Any> getOrNull(key: String): T? = getOrNull(key, T::class)

    init {
        folders.cache.mkdirs()
    }

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
}