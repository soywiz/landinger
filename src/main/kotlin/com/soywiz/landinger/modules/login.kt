package com.soywiz.landinger.modules

import com.soywiz.klock.DateTime
import com.soywiz.korinject.AsyncInjector
import com.soywiz.korio.util.encoding.unhexIgnoreSpaces
import com.soywiz.korte.dynamic.Dynamic2
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.request.uri
import io.ktor.response.respondRedirect
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.sessions.*

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
                getSponsorInfo(login, config.GH_SPONSOR_TOKEN ?: error("No github configured"))
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

    config.extraConfig["LOGIN_URL"] = object {
        @Suppress("unused")
        val github: String get() = "https://github.com/login/oauth/authorize?client_id=${config.GH_CLIENT_ID}&scope=read:user&redirect_uri=https://soywiz.com/login/oauth/authorize"
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
                it.call.sessions.get<UserSession>()
            } catch (e: IllegalStateException) {
                null
            }
            val logged = ((userSession?.login) != null)
            val login = userSession?.login ?: ""
            val price = userSession?.price ?: 0
            val platform = userSession?.platform ?: ""
            val SPONSOR_GITHUB_USERS = config.secrets["SPONSOR_GITHUB_USERS"]
            val extraSponsored = if (SPONSOR_GITHUB_USERS is Iterable<*>) {
                login in SPONSOR_GITHUB_USERS
            } else {
                false
            }
            it.isSponsor = logged && (price > 0 || extraSponsored)
            it.logged = logged
            //println("Info: $logged: $price, $extraSponsored, logged=$logged, sponsor=${it.isSponsor}")
            it.extraConfig["session"] = mapOf(
                "logged" to logged,
                "isSponsor" to it.isSponsor,
                "login" to login,
                "price" to price,
                "platform" to platform
            )
            //println("pageShown: $logged : $login")
            it.call.sessions.set(LastVisitedPageSession(it.call.request.uri))
        }
    }
}

data class UserSession(val login: String, val price: Int, val platform :String)
data class LastVisitedPageSession(val page: String)
