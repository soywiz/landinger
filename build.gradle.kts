import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin.Companion.isIncludeCompileClasspath
import proguard.gradle.*

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.4.2")
    }
}

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
    //implementation(libs.mapdb)
    implementation(libs.directory.watcher)
    //implementation(libs.lucene.core)
    //implementation(libs.lucene.queryparser)
    implementation(libs.flexmark.core)
    implementation(libs.flexmark.html)
    implementation(libs.flexmark.abbreviation)
    implementation(libs.flexmark.definition)
    implementation(libs.flexmark.footnotes)
    implementation(libs.flexmark.typographic)
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
    implementation(libs.korlibs.time)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.logback.classic)
    implementation(libs.yuicompressor)
    implementation("org.apache.logging.log4j:log4j-api:2.23.1")
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
        from(configurations.runtimeClasspath.get().files.map { if (it.isDirectory) it else zipTree(it) }) {
            exclude("com/sun/jna/**aix**/*")
            exclude("com/sun/jna/**freebsd**/*")
            exclude("com/sun/jna/**openbsd**/*")
            exclude("com/sun/jna/**sunos**/*")
            exclude("com/sun/jna/**mips**/*")
            exclude("com/sun/jna/**ppc**/*")
            exclude("com/sun/jna/**risc**/*")
            exclude("com/sun/jna/**s390x**/*")
        }
        with(jar.get())
        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    }

    val proguard by creating(ProGuardTask::class) {
        //configuration file('proguard.pro')
        injars(project.tasks.named("fatJar", Jar::class).flatMap { it.archiveFile })
        outjars(File(buildDir, "/libs/${project.name}.jar"))
        val javaHome = System.getProperty("java.home")
        libraryjars("$javaHome/lib/rt.jar")
        // Support newer java versions that doesn't have rt.jar
        libraryjars(project.fileTree("$javaHome/jmods/") {
            include("**/java.*.jmod")
        })

        dontwarn()
        ignorewarnings()
        //dontpreverify()
        //dontskipnonpubliclibraryclassmembers()

        // Main
        keep("class $baseMainClassName { *; }")

        // Optimization
        assumenosideeffects("class kotlin.jvm.internal.Intrinsics { static void checkParameterIsNotNull(java.lang.Object, java.lang.String); }")

        // Kotlin reflection
        keep("public class kotlin.reflect.jvm.internal.impl.serialization.deserialization.builtins.* { public *; }")
        keep("class kotlin.reflect.jvm.internal.impl.load.** { *; }")

        keep("class io.netty.util.ReferenceCountUtil { *; }")
        keepattributes("*Annotation*")
        dontwarn("ch.qos.logback.core.net.*")
        keep("class ch.qos.** { *; }")
        keep("class org.slf4j.** { *; }")
        keep("class org.apache.logging.log4j.** { *; }")
        keep("class com.vladsch.flexmark.util.sequence.** { *; }")
        dontwarn("org.apache.logging.log4j.**")
        dontwarn("org.apache.log4j.**")
        keepattributes("Signature")
        dontobfuscate()

        keep("@korlibs.inject.Singleton class * { public <init>(...); }")
        keep("@korlibs.inject.Prototype class * { public <init>(...); }")

        keep("enum com.vladsch.flexmark.** { *; }")
        keep("enum org.jsoup.nodes.** { *; }")


        //keep("class * implements com.sun.jna.** { *; }")
        //keep("class com.sun.jna.** { *; }")
        //keep("class !com.sun.jna.platform.win32.** { *; }")
        //keep("class !com.sun.jna.platform.wince.** { *; }")

        //keepnames("class com.sun.jna.** { *; }")
        //keepnames("class * extends com.sun.jna.** { *; }")
        //keepnames("class * implements com.sun.jna.Library { *; }")
        keepnames("class * extends korlibs.ffi.FFILib { *; }")
        keepnames("class * extends korlibs.korge.scene.Scene { *; }")
        keepnames("@korlibs.io.annotations.Keep class * { *; }")
        keepnames("@korlibs.annotations.Keep class * { *; }")
        keepnames("@korlibs.annotations.KeepNames class * { *; }")
        keepnames("@kotlinx.serialization class * { *; }")
        keepclassmembernames("class * { @korlibs.inject.Singleton *; }")
        keepclassmembernames("class * { @korlibs.inject.Prototype *; }")
        keepclassmembernames("class * { @korlibs.io.annotations.Keep *; }")
        keepclassmembernames("@korlibs.io.annotations.Keep class * { *; }")
        keepclassmembernames("@korlibs.io.annotations.Keep interface * { *; }")
        keepclassmembernames("@korlibs.annotations.Keep class * { *; }")
        keepclassmembernames("@korlibs.annotations.Keep interface * { *; }")
        keepclassmembernames("@korlibs.annotations.KeepNames class * { *; }")
        keepclassmembernames("@korlibs.annotations.KeepNames interface * { *; }")
        keepclassmembernames("enum * { public *; }")
        //keepnames("@korlibs.io.annotations.Keep interface *")
        //keepnames("class korlibs.render.platform.INativeGL")
        //task.keepnames("class org.jcodec.** { *; }")
        keepattributes()
        keep("class * extends korlibs.ffi.FFILib { *; }")
        keep("@korlibs.io.annotations.Keep class * { *; }")
        keep("@korlibs.annotations.Keep class * { *; }")
        keep("@kotlinx.serialization class * { *; }")
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
