import org.gradle.kotlin.dsl.*

plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    application
}
group = "com.soywiz.landinger"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(libs.directory.watcher)
    implementation(libs.lucene.core)
    implementation(libs.lucene.queryparser)
    implementation(libs.flexmark.all)
    implementation(libs.snakeyaml)
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation(libs.freemarker)
    implementation(libs.slack.client)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.caching.headers)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.client.core.jvm)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.jackson)
    implementation(libs.korge.core)
    implementation(libs.kminiorm)
    implementation(libs.kminiorm.jdbc)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.logback.classic)
    implementation(libs.yuicompressor)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        //jvmTarget = "1.8"
    }
}

val baseMainClassName = "com.soywiz.landinger.MainKt"

application {
    mainClass = baseMainClassName
    //applicationDefaultJvmArgs = listOf("--help")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

tasks {
    val fatJar by creating(Jar::class) {
        manifest {
            attributes(mapOf("Main-Class" to baseMainClassName))
        }
        archiveBaseName.set("app")
        archiveVersion.set("")
        from(configurations.runtimeClasspath.get().files.map { if (it.isDirectory) it else zipTree(it) })
        with(jar.get())
        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    }

    //val run by creating(JavaExec::class) {}

    val jarFile = fatJar.outputs.files.first()
    val server = "soywiz"
    //val domain = "programar.ovh"
    //val baseDir = "/home/virtual/seo/programar.ovh"
    data class Entry(val domain: String, val baseDir: String) {
        val baseOut = "$server:$baseDir"
    }
    val entries = listOf(
        Entry("soywiz.com", "/home/virtual/soywiz/soywiz.com"),
        Entry("blog.korge.org", "/home/virtual/korge/blog.korge.org"),
    )

    val publishDockerCompose by creating {
        doLast {
            for (entry in entries) {
                exec { commandLine("scp", file("Dockerfile"), "${entry.baseOut}/Dockerfile") }
                exec { commandLine("scp", file("docker-compose.yml"), "${entry.baseOut}/docker-compose.yml") }
                file(".env").writeText("VIRTUAL_HOST=${entry.domain}\nVIRTUAL_PORT=8080\n")
                exec { commandLine("scp", file(".env"), "${entry.baseOut}/.env") }
            }
        }
    }

    //val publishContent by creating {
    //    doLast {
    //        exec { commandLine("rsync", "-avz", "$contentDir/", "$baseOut/app/content/") }
    //    }
    //}

    val publishFatJar by creating {
        dependsOn(fatJar)
        //dependsOn(publishContent)
        doLast {
            for (entry in entries) {
                exec { commandLine("scp", jarFile, "${entry.baseOut}/app/${jarFile.name}") }
            }
        }
    }

    val restartDockerCompose by creating {
        dependsOn(fatJar)
        doLast {
            for (entry in entries) {
                exec { commandLine("ssh", server, "/bin/bash", "-c", "'cd ${entry.baseDir}; docker-compose restart'") }
            }
        }
    }

    val publish by creating {
        dependsOn(publishDockerCompose, publishFatJar)
        finalizedBy(restartDockerCompose)
    }
}
