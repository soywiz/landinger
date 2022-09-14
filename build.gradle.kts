import org.gradle.kotlin.dsl.*

plugins {
    kotlin("jvm") version "1.6.10"
    application
}
group = "com.soywiz.landinger"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

val klockVersion: String by project
val korteVersion: String by project
val korinjectVersion: String by project
val kryptoVersion: String by project
val korimVersion: String by project
val kminiormVersion: String by project
val ktorVersion: String by project

dependencies {
    implementation("io.methvin:directory-watcher:0.16.1")
    implementation("org.apache.lucene:lucene-core:9.3.0")
    implementation("org.apache.lucene:lucene-queryparser:9.3.0")
    implementation("com.vladsch.flexmark:flexmark-all:0.64.0")
    implementation("org.yaml:snakeyaml:1.30")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.freemarker:freemarker:2.3.31")
    implementation("com.hubspot.slack:slack-client:1.12")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-partial-content:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")
    implementation("io.ktor:ktor-server-caching-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-jackson:$ktorVersion")
    //implementation("io.ktor:ktor-html-builder:$ktorVersion")
    implementation("com.soywiz.korlibs.klock:klock-jvm:$klockVersion")
    implementation("com.soywiz.korlibs.krypto:krypto-jvm:$kryptoVersion")
    implementation("com.soywiz.korlibs.korte:korte-jvm:$korteVersion")
    //implementation("com.soywiz.korlibs.korte:korte-korio-jvm:$korteVersion")
    implementation("com.soywiz.korlibs.korim:korim-jvm:$korimVersion")
    implementation("com.soywiz.korlibs.korinject:korinject-jvm:$korinjectVersion")
    implementation("com.soywiz.korlibs.kminiorm:kminiorm-jvm:$kminiormVersion")
    implementation("com.soywiz.korlibs.kminiorm:kminiorm-jdbc-jvm:$kminiormVersion")
    implementation("org.xerial:sqlite-jdbc:3.39.3.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.4")
    implementation("ch.qos.logback:logback-classic:1.4.0")
    implementation("com.yahoo.platform.yui:yuicompressor:2.4.8")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

val baseMainClassName = "com.soywiz.landinger.MainKt"

application {
    mainClassName = baseMainClassName
    //applicationDefaultJvmArgs = listOf("--help")
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
                exec { commandLine("rsync", "-avz", jarFile, "${entry.baseOut}/app/") }
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
