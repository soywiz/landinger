package com.soywiz.landinger.modules

import korlibs.inject.AsyncInjector
import korlibs.encoding.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import korlibs.time.*

private val ConfigService.GH_CLIENT_ID get() = getSecretOrEnvString("GH_CLIENT_ID")
private val ConfigService.GH_CLIENT_SECRET get() = getSecretOrEnvString("GH_CLIENT_SECRET")
private val ConfigService.GH_SPONSOR_TOKEN get() = getSecretOrEnvString("GH_SPONSOR_TOKEN")
private val ConfigService.SECRET_SESSION_ENCRYPT_KEY get() = getSecretOrEnvString("SECRET_SESSION_ENCRYPT_KEY")?.unhexIgnoreSpaces
private val ConfigService.SECRET_SESSION_AUTH_KEY get() = getSecretOrEnvString("SECRET_SESSION_AUTH_KEY")?.unhexIgnoreSpaces

suspend fun Application.installLogin(injector: AsyncInjector) {
    val config = injector.get<ConfigService>()
    val SECRET_SESSION_ENCRYPT_KEY = config.SECRET_SESSION_ENCRYPT_KEY
    val SECRET_SESSION_AUTH_KEY = config.SECRET_SESSION_AUTH_KEY
    if (SECRET_SESSION_ENCRYPT_KEY == null || SECRET_SESSION_AUTH_KEY == null) return

    val pageShownBus = injector.get<PageShownBus>()

    val sponsorInfoCache = LinkedHashMap<String, SponsorInfo>()

    suspend fun getCachedSponsor(login: String): SponsorInfo {
        return sponsorInfoCache.getOrPut(login) {
            try {
                getSponsorInfo(login, config.GH_SPONSOR_TOKEN ?: error("No github configured"), config)
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

    val sitePrefix = config.config["SITE_PREFIX"]?.toString() ?: "https://soywiz.com/"

    config.extraConfig["LOGIN_URL"] = object {
        @Suppress("unused")
        val github: String get() = "https://github.com/login/oauth/authorize?client_id=${config.GH_CLIENT_ID}&scope=read:user&redirect_uri=${sitePrefix}login/oauth/authorize"
    }

    install(Sessions) {
        cookie<UserSession>("SESSION") {
            cookie.path = "/"
            transform(SessionTransportTransformerEncrypt(SECRET_SESSION_ENCRYPT_KEY, SECRET_SESSION_AUTH_KEY))
        }
        cookie<LastVisitedPageSession>("LAST_VISITED_PAGE") {
            cookie.path = "/"
            transform(SessionTransportTransformerEncrypt(SECRET_SESSION_ENCRYPT_KEY, SECRET_SESSION_AUTH_KEY))
        }
    }
    routing {
        println("!! Configured sessions")
        get("/login/fake") {
            if (!config.startConfig.debug) throw NotFoundException()
            val price = call.request.queryParameters["price"]?.toIntOrNull() ?: error("Missing price")
            call.sessions.set(UserSession("debug-$price", price, "github"))
            call.respondRedirect(call.sessions.get<LastVisitedPageSession>()?.page ?: "/")
        }
        get("/login/oauth/authorize") {
            val code = call.request.queryParameters["code"] ?: error("Missing code")
            val GH_CLIENT_ID = config.GH_CLIENT_ID ?: error("No github configured")
            val GH_CLIENT_SECRET = config.GH_CLIENT_SECRET ?: error("No github configured")
            val accessToken = oauthGetAccessToken(code, GH_CLIENT_ID, GH_CLIENT_SECRET)
            val login = getUserLogin(accessToken)
            sponsorInfoCache.remove(login)
            val sponsorInfo = getCachedSponsor(login)
            call.sessions.set(UserSession(login, sponsorInfo.price, "github"))
            call.respondRedirect(call.sessions.get<LastVisitedPageSession>()?.page ?: "/")
        }
        get("/logout") {
            //println("logout")
            call.sessions.clear<UserSession>()
            call.respondRedirect(call.sessions.get<LastVisitedPageSession>()?.page ?: "/")
        }
        pageShownBus.pageShown { it ->
            val userSession = try {
                it.call?.sessions?.get<UserSession>()
            } catch (e: IllegalStateException) {
                null
            }
            val logged = ((userSession?.login) != null)
            val login = userSession?.login ?: ""
            val price = userSession?.price ?: 0
            val platform = userSession?.platform ?: ""
            it.isSponsor = logged && (price > 0)
            it.logged = logged
            //println("Info: $logged: $price, $extraSponsored, logged=$logged, sponsor=${it.isSponsor}")
            config.secrets
            it.extraConfig["session"] = mapOf(
                "logged" to logged,
                "isSponsor" to it.isSponsor,
                "login" to login,
                "price" to price,
                "platform" to platform
            )
            it.extraConfig["debug"] = config.startConfig.debug
            //println("pageShown: $logged : $login")
            it.call?.sessions?.set(LastVisitedPageSession(it.call.request.uri))
        }
    }
}

data class UserSession(val login: String, val price: Int, val platform :String)
data class LastVisitedPageSession(val page: String)
