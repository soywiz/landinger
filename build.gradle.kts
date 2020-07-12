import org.gradle.kotlin.dsl.*

plugins {
    kotlin("jvm") version "1.3.72"
    application
}
group = "com.soywiz.landinger"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
    jcenter()
}

dependencies {
    //val kotlinx_html_version = "0.7.1"
    val ktorVersion = "1.3.0"

    // include for server side
    //implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:${kotlinx_html_version}")

    implementation("org.apache.lucene:lucene-core:8.5.0")
    implementation("org.apache.lucene:lucene-queryparser:8.5.0")

    implementation("com.vladsch.flexmark:flexmark-all:0.61.6")
    implementation("org.yaml:snakeyaml:1.25")

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    //implementation("mysql:mysql-connector-java:8.0.19")
    implementation("org.freemarker:freemarker:2.3.29")
    //implementation('io.vertx:vertx-web:3.8.5')
    //implementation('io.vertx:vertx-lang-kotlin:3.8.5')
    //implementation('io.vertx:vertx-lang-kotlin-coroutines:3.8.5')
    implementation("com.hubspot.slack:slack-client:1.6")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-jackson:$ktorVersion")
    implementation("io.ktor:ktor-html-builder:$ktorVersion")
    //implementation('com.soywiz.korlibs.korio:korio-jvm:1.9.8')
    implementation("com.soywiz.korlibs.klock:klock-jvm:1.10.5")

    val korteVersion = "1.10.6"
    implementation("com.soywiz.korlibs.korte:korte-jvm:$korteVersion")
    implementation("com.soywiz.korlibs.korte:korte-korio-jvm:$korteVersion")

    implementation("com.soywiz.kminiorm:kminiorm:0.5.0")
    implementation("com.soywiz.kminiorm:kminiorm-jdbc:0.5.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.10.2")
    implementation("com.h2database:h2:1.4.199")
    testImplementation("junit:junit:4.13")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")

    implementation("ch.qos.logback:logback-classic:1.2.3")

    implementation("com.yahoo.platform.yui:yuicompressor:2.4.8")

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
    }

    //val run by creating(JavaExec::class) {}

    val jarFile = fatJar.outputs.files.first()
    val server = "soywiz2"
    val baseDir = "/home/virtual/seo/programar.ovh"
    val baseOut = "$server:$baseDir"
    val contentDir = File(projectDir, "content")

    val publishDockerCompose by creating {
        doLast {
            exec { commandLine("scp", file("Dockerfile"), "$baseOut/Dockerfile") }
            exec { commandLine("scp", file("docker-compose.yml"), "$baseOut/docker-compose.yml") }
            File(".env").writeText(
                "VIRTUAL_HOST=programar.ovh\n" +
                "VIRTUAL_PORT=8080\n"
            )
            exec { commandLine("scp", file(".env"), "$baseOut/.env") }
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
            exec { commandLine("rsync", "-avz", jarFile, "$baseOut/app/") }
        }
    }

    val restartDockerCompose by creating {
        dependsOn(fatJar)
        doLast {
            exec { commandLine("ssh", server, "/bin/bash", "-c", "'cd $baseDir; docker-compose restart'") }
        }
    }

    val publish by creating {
        dependsOn(publishDockerCompose, publishFatJar)
        finalizedBy(restartDockerCompose)
    }
}