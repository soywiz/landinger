package com.soywiz.landinger.modules

import com.soywiz.kds.linkedHashMapOf
import com.soywiz.korinject.Singleton
import com.soywiz.landinger.Folders
import com.soywiz.landinger.util.takeIfExists
import com.soywiz.landinger.util.yaml

data class Config(
    var port: Int = System.getenv("VIRTUAL_PORT")?.toIntOrNull() ?: 8080,
    var host: String = "127.0.0.1",
    var contentDir: String = "content"
)

@Singleton
class ConfigService(val folders: Folders) {
    @Volatile
    var config: Map<String, Any?> = mapOf()

    val extraConfig = LinkedHashMap<String, Any?>()

    @Volatile
    var siteData: Map<String, Any?> = mapOf()

    fun reloadConfig() {
        config = yaml.load<Map<String, Any?>>((folders.configYml.takeIfExists()?.readText() ?: "").reader())
        this.siteData = linkedHashMapOf<String, Any?>().also { siteData ->
            for (file in folders.data.walkTopDown()) {
                if (file.extension == "yml") {
                    siteData[file.nameWithoutExtension] = yaml.load<Any?>(file.readText().reader())
                }
            }
        }
    }

    fun getConfigOrEnvString(key: String): String? = System.getenv(key) ?: config["env"]?.toString()
}
