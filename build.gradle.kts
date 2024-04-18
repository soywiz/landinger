import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin.Companion.isIncludeCompileClasspath

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
    implementation(libs.korlibs.io)
    implementation(libs.korlibs.image)
    implementation(libs.korlibs.inject)
    implementation(libs.korlibs.template)
    implementation(libs.kminiorm)
    implementation(libs.kminiorm.jdbc)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.logback.classic)
    implementation(libs.yuicompressor)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation(libs.kotlinx.coroutines.test)
}

val baseMainClassName = "com.soywiz.landinger.MainKt"

application {
    mainClass = baseMainClassName
    //applicationDefaultJvmArgs = listOf("--help")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

tasks {
    val generate by creating(JavaExec::class) {
        group = "application"
        mainClass = baseMainClassName
        classpath = sourceSets["main"].runtimeClasspath
        args("-g")
    }

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

    //val jarFile = fatJar.outputs.files.first()

    val publish by creating {
        dependsOn(fatJar)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        this.suppressWarnings.set(true)
        this.jvmTarget.set(JvmTarget.JVM_1_8)
    }
}
