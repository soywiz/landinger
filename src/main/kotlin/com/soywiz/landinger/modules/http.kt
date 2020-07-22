package com.soywiz.landinger.modules

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import java.net.URL

private val httpClient = HttpClient(OkHttp)

suspend fun httpRequestGetString(url: URL): String {
    return httpClient.get<String>(url) {
        header("Accept", "application/json")
    }
}