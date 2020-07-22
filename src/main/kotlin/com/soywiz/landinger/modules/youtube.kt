package com.soywiz.landinger.modules

import com.fasterxml.jackson.annotation.JsonIgnore
import com.soywiz.klock.*
import com.soywiz.korinject.Singleton
import com.soywiz.korio.lang.UTF8
import com.soywiz.korio.lang.toByteArray
import com.soywiz.korio.net.QueryString
import com.soywiz.korio.serialization.json.Json
import com.soywiz.krypto.sha1
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import java.net.URL
import java.util.Date

private val youtubeClient = HttpClient(OkHttp)

data class YoutubeInfo(
    val id: String,
    val title: String,
    val description: String,
    val publishedUnix: Long,
    val durationMilliseconds: Long,
    val thumbnails: List<Thumbnail>
) {
    @get:JsonIgnore
    val thumbnail get() = thumbnails.first()
    @get:JsonIgnore
    val durationStr get() = durationMilliseconds.toDouble().milliseconds.toTimeString()
    data class Thumbnail(val kind: String, val url: String, val width: Int, val height: Int)
}

@Singleton
class YoutubeService(private val cache: Cache, private val config: ConfigService) {

    // "PT42M6S"
    fun parseDuration(duration: String): TimeSpan {
        val regex = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")
        val result = regex.matchEntire(duration) ?: error("Invalid duration format")
        val hours = result.groupValues[1].toIntOrNull()
        val minutes = result.groupValues[2].toIntOrNull()
        val seconds = result.groupValues[3].toIntOrNull()
        var out = 0.seconds
        if (hours != null) out += hours.hours
        if (minutes != null) out += minutes.minutes
        if (seconds != null) out += seconds.seconds
        return out
    }

    suspend fun getYoutubeVideoInfo(ids: List<String>, apiKey: String? = config.secrets["youtube_api_key"]?.toString()): List<YoutubeInfo> {
        fun getInfoKey(id: String) = "youtube.info.$id"
        if (apiKey.isNullOrEmpty()) error("API Key not provided")
        val uncachedIds = ids.filter { !cache.has(getInfoKey(it)) }

        //println("uncachedIds: $uncachedIds")

        if (uncachedIds.isNotEmpty()) {
            val url = "https://www.googleapis.com/youtube/v3/videos?" + QueryString.encode(
                mapOf(
                    "part" to listOf("contentDetails", "snippet"),
                    "id" to uncachedIds,
                    "key" to listOf(apiKey)
                )
            )

            //println("request=$url")
            val urlHash = url.toByteArray(UTF8).sha1().hex
            val result = cache.get("youtube.request.$urlHash") {
                httpRequestGetString(URL(url))
            }
            //val result = MOCKED_REQUEST
            val resultJson = Json.parse(result)
            Dynamic {
                for (item in resultJson["items"].list) {
                    val id = item["id"].str
                    val title = item["snippet"]["title"].str
                    val description = item["snippet"]["description"].str
                    val publishedDate = DateFormat.parse(item["snippet"]["publishedAt"].str).utc
                    val duration = parseDuration(item["contentDetails"]["duration"].str)
                    val thumbnails = item["snippet"]["thumbnails"].entries().map { (key: Any?, value: Any?) ->
                        YoutubeInfo.Thumbnail(key.str, value["url"].str, value["width"].int, value["height"].int)
                    }
                    cache.put(
                        getInfoKey(id),
                        YoutubeInfo(
                            id,
                            title,
                            description,
                            publishedDate.unixMillisLong,
                            duration.millisecondsLong,
                            thumbnails
                        )
                    )
                }
            }
        }
        //https://www.googleapis.com/youtube/v3/videos?part=contentDetails&part=snippet&id=mfJtWm5UddM&id=fCE7-ofMVbM&key=[YOUR_API_KEY]
        // -header 'Accept: application/json' \
        return ids.map { cache.getOrNull<YoutubeInfo>(getInfoKey(it)) }.filterNotNull()
    }
}

private val MOCKED_REQUEST = """
{
  "kind": "youtube#videoListResponse",
  "etag": "CRXmw1gKlfUKv4COl3gh7EYh3Nk",
  "items": [
    {
      "kind": "youtube#video",
      "etag": "2gEPMONR6qqrdq_fAwfKXh3FN5E",
      "id": "mfJtWm5UddM",
      "snippet": {
        "publishedAt": "2020-07-22T16:44:48Z",
        "channelId": "UCTUT8yAv8FICjysc5nyGazQ",
        "title": "KorGE: Converting Spine libgdx to korge [Part 20] - Spine premultiplied mistery",
        "description": "",
        "thumbnails": {
          "default": {
            "url": "https://i.ytimg.com/vi/mfJtWm5UddM/default.jpg",
            "width": 120,
            "height": 90
          },
          "medium": {
            "url": "https://i.ytimg.com/vi/mfJtWm5UddM/mqdefault.jpg",
            "width": 320,
            "height": 180
          },
          "high": {
            "url": "https://i.ytimg.com/vi/mfJtWm5UddM/hqdefault.jpg",
            "width": 480,
            "height": 360
          },
          "standard": {
            "url": "https://i.ytimg.com/vi/mfJtWm5UddM/sddefault.jpg",
            "width": 640,
            "height": 480
          }
        },
        "channelTitle": "soywiz",
        "categoryId": "20",
        "liveBroadcastContent": "none",
        "localized": {
          "title": "KorGE: Converting Spine libgdx to korge [Part 20] - Spine premultiplied mistery",
          "description": ""
        }
      },
      "contentDetails": {
        "duration": "PT42M6S",
        "dimension": "2d",
        "definition": "hd",
        "caption": "false",
        "licensedContent": false,
        "contentRating": {},
        "projection": "rectangular"
      }
    },
    {
      "kind": "youtube#video",
      "etag": "7aixhJiUadaLLbWwlHs6lwx9EZY",
      "id": "fCE7-ofMVbM",
      "snippet": {
        "publishedAt": "2020-07-22T15:29:41Z",
        "channelId": "UCTUT8yAv8FICjysc5nyGazQ",
        "title": "KorGE: Converting Spine libgdx to korge [Part 19] - Final Cleanups",
        "description": "",
        "thumbnails": {
          "default": {
            "url": "https://i.ytimg.com/vi/fCE7-ofMVbM/default.jpg",
            "width": 120,
            "height": 90
          },
          "medium": {
            "url": "https://i.ytimg.com/vi/fCE7-ofMVbM/mqdefault.jpg",
            "width": 320,
            "height": 180
          },
          "high": {
            "url": "https://i.ytimg.com/vi/fCE7-ofMVbM/hqdefault.jpg",
            "width": 480,
            "height": 360
          },
          "standard": {
            "url": "https://i.ytimg.com/vi/fCE7-ofMVbM/sddefault.jpg",
            "width": 640,
            "height": 480
          }
        },
        "channelTitle": "soywiz",
        "categoryId": "20",
        "liveBroadcastContent": "none",
        "localized": {
          "title": "KorGE: Converting Spine libgdx to korge [Part 19] - Final Cleanups",
          "description": ""
        }
      },
      "contentDetails": {
        "duration": "PT55M49S",
        "dimension": "2d",
        "definition": "hd",
        "caption": "false",
        "licensedContent": false,
        "contentRating": {},
        "projection": "rectangular"
      }
    }
  ],
  "pageInfo": {
    "totalResults": 2,
    "resultsPerPage": 2
  }
}
""".trimIndent()
