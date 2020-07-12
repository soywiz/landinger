package com.soywiz.landinger.modules

import com.fasterxml.jackson.module.kotlin.*
import com.soywiz.klock.*
import com.soywiz.korio.lang.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.content.*
import java.net.*

val GH_CLIENT_ID by lazy { System.getenv("GH_CLIENT_ID") }
val GH_CLIENT_SECRET by lazy { System.getenv("GH_CLIENT_SECRET") }
val GH_SPONSOR_TOKEN by lazy { System.getenv("GH_SPONSOR_TOKEN") }

private val oauthHttpClient = HttpClient(OkHttp)

suspend fun oauthGetAccessToken(code: String): String {
    val result = oauthHttpClient.post<String>(URL("https://github.com/login/oauth/access_token")) {
        body = FormDataContent(Parameters.build {
            append("client_id", GH_CLIENT_ID)
            append("client_secret", GH_CLIENT_SECRET)
            append("code", code)
        })
    }
    val params = result.parseUrlEncodedParameters()
    return params["access_token"] ?: error("Can't get access token")
}

suspend fun oauthGetUserLogin(access_token: String): String {
    val result = oauthHttpClient.get<String>(URL("https://api.github.com/user")) {
        header("Authorization", "token $access_token")
    }
    val data = jsonMapper.readValue<Map<String, Any?>>(result)
    return data["login"].toString()
}

val jsonMapper = jacksonObjectMapper()

suspend fun graphqlCall(access_token: String, query: String): Map<String, Any?> {
    val result = oauthHttpClient.post<String>(URL("https://api.github.com/graphql")) {
        header("Authorization", "bearer $access_token")
        body = TextContent(jsonMapper.writeValueAsString(mapOf("query" to query)), contentType = ContentType.Any)
    }
    println("result: $result")
    return jsonMapper.readValue<Map<String, Any?>>(result)
}

object Dynamic {
    operator fun <T> invoke(block: Dynamic.() -> T): T = block()

    operator fun Any?.get(key: String): Any? = when (this) {
        is Map<*, *> -> (this as Map<String, Any?>)[key]
        else -> null
    }

    val Any?.str get() = this.toString()
    val Any?.int get() = when (this) {
        is Number -> this.toInt()
        else -> this.toString().toIntOrNull() ?: 0
    }
    val Any?.list: List<Any?> get() = when (this) {
        is Iterable<*> -> this.toList()
        else -> listOf()
    }
}

suspend fun getUserLogin(access_token: String): String {
    val data = graphqlCall(
        access_token,
        "query { viewer { login } }"
    )
    return Dynamic { data["data"]["viewer"]["login"].str }
}

data class SponsorInfo(val login: String, val price: Int, val date: DateTime)

suspend fun getSponsorInfo(login: String, access_token: String = GH_SPONSOR_TOKEN): SponsorInfo {
    val data = graphqlCall(
        access_token, """
        query { 
          user(login: ${login.quoted}) {
            sponsorshipsAsSponsor(first: 100) {
              edges {
                node {
                  sponsorable {
                    sponsorsListing {
                      slug
                    }
                  }
                  tier {
                    monthlyPriceInDollars
                  }
                }
              }
            }
          }
        }
    """.trimIndent()
    )

    var sponsorPrice: Int = 0
    Dynamic {
        for (edge in data["data"]["user"]["sponsorshipsAsSponsor"]["edges"].list) {
            val slug = edge["node"]["sponsorable"]["sponsorsListing"]["slug"].str
            val price = edge["node"]["tier"]["monthlyPriceInDollars"].int
            if (slug == "sponsors-soywiz") {
                sponsorPrice = price
            }
        }
    }
    return SponsorInfo(
        login,
        if (login == "soywiz") +1 else sponsorPrice,
        DateTime.now()
    )
}

data class LastVisitedPageSession(val page: String)
data class UserSession(val login: String)

val sponsorInfoCache = LinkedHashMap<String, SponsorInfo>()

suspend fun getCachedSponsor(login: String): SponsorInfo {
    return sponsorInfoCache.getOrPut(login) {
        try {
            getSponsorInfo(login)
        } catch (e: Throwable) {
            SponsorInfo(login, 0, DateTime.now())
        }
    }.also {
        val timeSinceCached = DateTime.now() - it.date
        if (timeSinceCached.days >= 7.0) {
            sponsorInfoCache.remove(it.login)
        }
    }
}

val LOGIN_URL = "https://github.com/login/oauth/authorize?client_id=$GH_CLIENT_ID&scope=read:user"
