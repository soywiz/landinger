package com.soywiz.landinger.util

import korlibs.datastructure.reader
import korlibs.io.util.reader
import kotlin.reflect.KClass

class CliParser(val title: String = "CLI") {
    data class Entry(val names: List<String>, val desc: String, val nargs: Int = 0, val block: (String) -> Unit)

    val entries = arrayListOf<Entry>()
    val entriesByName = LinkedHashMap<String, Entry>()

    fun registerSwitch(vararg names: String, desc: String = "", nargs: Int, block: (String) -> Unit) = this.apply {
        val nameList = names.toList()
        val entry = Entry(nameList, desc, nargs, block)
        entries += entry
        for (name in nameList) entriesByName[name] = entry
    }

    fun <T : Any> registerSwitch(clazz: KClass<T>, vararg names: String, desc: String = "", block: (T) -> Unit) = this.apply {
        registerSwitch(*names, desc = desc, nargs = if (clazz == Unit::class) 0 else 1) {
            val value = when (clazz) {
                Unit::class -> Unit
                String::class -> it
                Boolean::class -> it.toIntOrNull()?.let { it != 0 } ?: it.toBoolean()
                Int::class -> (it.toIntOrNull() ?: 0)
                else -> error("Error")
            } as T
            block(value)
        }
    }

    @JvmName("registerSwitch2")
    inline fun <reified T : Any> registerSwitch(vararg names: String, desc: String = "", noinline block: (T) -> Unit) =
        registerSwitch(T::class, *names, desc = desc, block = block)

    fun registerDefault(desc: String = "", block: (String) -> Unit) = registerSwitch("", desc = desc, nargs = 0, block = block)

    fun parse(args: Array<String>) = parse(args.toList())
    fun parse(args: String) = parse(parseString(args))

    fun parse(args: List<String>) {
        val r = args.reader()
        while (r.hasMore) {
            val item = r.read()
            val entry = entriesByName[item]
            val args = if (entry != null) (0 until entry.nargs).map { r.read() } else listOf()
            if (entry != null) {
                entry.block(args.getOrElse(0) { "" })
            } else {
                entriesByName[""]?.block?.invoke(item)
            }
        }
    }

    fun showHelp() {
        println("$title cli")
        println("")
        for (entry in entries) {
            println("  ${entry.names.joinToString(", ")} - ${entry.desc}")
        }
    }

    companion object {
        fun parseString(str: String): List<String> {
            val params = arrayListOf<String>()
            val r = str.reader()
            var currentStr = StringBuilder()
            while (r.hasMore) {
                val c = r.read()
                when (c) {
                    '"' -> {
                        currentStr.clear()
                        loop@while (r.hasMore) {
                            val c2 = r.read()
                            when (c2) {
                                '\\' -> currentStr.append(r.read())
                                '"' -> break@loop
                                else -> currentStr.append(c2)
                            }
                        }
                    }
                    ' ', '\t', '\r', '\n' -> {
                        params += currentStr.toString()
                    }
                    '\\' -> {
                        currentStr.append(r.read())
                    }
                    else -> {
                        currentStr.append(c)
                    }
                }
            }
            return params
        }
    }
}