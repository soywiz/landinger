package com.soywiz.landinger.modules

import korlibs.inject.Singleton
import com.soywiz.landinger.*
import com.soywiz.landinger.util.takeIfExists
import com.soywiz.landinger.util.yaml
import korlibs.datastructure.*

data class Config(
    var port: Int = System.getenv("VIRTUAL_PORT")?.toIntOrNull() ?: 8080,
    var host: String = "127.0.0.1",
    var contentDir: String = "content",
    var debug: Boolean = false,
)

@Singleton
class ConfigService(val folders: Folders, val startConfig: Config) {
    @Volatile
    var config: Map<String, Any?> = mapOf()

    @Volatile
    var secrets: Map<String, Any?> = mapOf()

    val extraConfig = LinkedHashMap<String, Any?>()

    @Volatile
    var siteData: Map<String, Any?> = mapOf()

    val SPONSOR_GITHUB_USERS_5: Set<String> get() = (secrets["SPONSOR_GITHUB_USERS_5"] as? Iterable<String>?)?.toSet() ?: emptySet()
    val SPONSOR_GITHUB_USERS_10: Set<String> get() = (secrets["SPONSOR_GITHUB_USERS_10"] as? Iterable<String>?)?.toSet() ?: emptySet()
    val SPONSOR_GITHUB_USERS_15: Set<String> get() = (secrets["SPONSOR_GITHUB_USERS_15"] as? Iterable<String>?)?.toSet() ?: emptySet()
    val SPONSOR_GITHUB_USERS_30: Set<String> get() = (secrets["SPONSOR_GITHUB_USERS_30"] as? Iterable<String>?)?.toSet() ?: emptySet()

    fun reloadConfig() {
        config = yaml.load<Map<String, Any?>?>((folders.configYml.takeIfExists()?.readText() ?: "").reader()) ?: mapOf()
        secrets = yaml.load<Map<String, Any?>?>((folders.secretsYml.takeIfExists()?.readText() ?: "").reader()) ?: mapOf()
        this.siteData = linkedHashMapOf<String, Any?>().also { siteData ->
            for (file in folders.data.walkTopDown()) {
                if (file.extension == "yml") {
                    siteData[file.nameWithoutExtension] = yaml.load<Any?>(file.readText().reader())
                }
            }
        }
    }

    fun getConfigOrEnvString(key: String): String? = System.getenv(key) ?: config[key]?.toString()
    fun getSecretOrEnvString(key: String): String? = System.getenv(key) ?: secrets[key]?.toString()
}
